package dev.ngb.platform.access;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.access.application.role.command.CreateRoleUseCase;
import dev.ngb.platform.access.application.role.command.ManageRoleUseCase;
import dev.ngb.platform.access.application.user.command.CreateUserUseCase;
import dev.ngb.platform.access.domain.role.Role;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.support.UncommittedRace;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assigning a role while replacing that role's permissions is the race a user-level version bump cannot fix: the
 * assign computes the user's permissions from the role's old set, and the replace never sees the new assignment.
 * The role lock makes the two serialise, so the final published state holds the new permissions.
 */
@SpringBootTest(classes = Application.class)
@Import({PlatformContainerConfig.class, ConcurrentUserAccessIntegrationTest.FakeIdentities.class})
class RolePermissionRaceIntegrationTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired CreateTenantUseCase createTenant;
    @Autowired CreateUserUseCase createUser;
    @Autowired CreateRoleUseCase createRole;
    @Autowired ManageRoleUseCase manageRole;
    @Autowired TenantContext tenantContext;
    @Autowired UnitOfWork unitOfWork;
    @Autowired OwnerDatabase owner;

    @Test
    void anAssignRacingAPermissionReplacementPublishesTheNewPermissions() throws Exception {
        Tenant tenant = newTenant();
        User user = newUser(tenant);
        Role role = onPlatform(() -> createRole.execute(new CreateRoleUseCase.Command(
                tenant.id(), PermissionScope.TENANT, "Operator-" + suffix(), Set.of("billing:invoice:read"))));
        long before = latestVersion(user.id().value());

        UncommittedRace.run(
                unitOfWork, tenantContext, RequestScope.platform(), owner, "%for no key update%",
                () -> assign(role, user),
                () -> replacePermissions(role, Set.of("billing:invoice:read", "billing:wallet:read")));

        List<JsonNode> events = eventsSince(user.id().value(), before);
        assertThat(events).as("both the assign and the fan-out publish a new state").isNotEmpty();
        assertThat(events.stream().map(payload -> payload.get("version").asLong()).toList()).doesNotHaveDuplicates();
        assertThat(permissions(events.getLast()))
                .as("the final state was computed after the replacement committed")
                .containsExactlyInAnyOrder("billing:invoice:read", "billing:wallet:read");
    }

    private Role assign(Role role, User user) {
        return manageRole.execute(new ManageRoleUseCase.Command(
                role.id(), ManageRoleUseCase.Action.ASSIGN, Set.of(), null, user.id(), ExpectedVersion.any()));
    }

    private Role replacePermissions(Role role, Set<String> permissions) {
        return manageRole.execute(new ManageRoleUseCase.Command(
                role.id(), ManageRoleUseCase.Action.REPLACE_PERMISSIONS, permissions, null, null, ExpectedVersion.any()));
    }

    private Tenant newTenant() {
        return onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(
                "rpr" + suffix(), "Role race", "user/tester", "req-1")));
    }

    private User newUser(Tenant tenant) {
        String email = "user-" + suffix() + "@example.test";
        return onPlatform(() -> createUser.execute(new CreateUserUseCase.Command(
                tenant.code().value(), tenant.id(), PermissionScope.TENANT, email, "User", "change-me")));
    }

    private List<JsonNode> events(UUID userId) {
        return owner.jdbc().query(
                "select payload::text from outbox where type = 'user-access.changed' and payload ->> 'user_id' = ?"
                        + " order by (payload ->> 'version')::bigint",
                (rs, rowNum) -> json.readTree(rs.getString(1)),
                userId.toString());
    }

    private List<JsonNode> eventsSince(UUID userId, long afterVersion) {
        return events(userId).stream().filter(payload -> payload.get("version").asLong() > afterVersion).toList();
    }

    private long latestVersion(UUID userId) {
        List<JsonNode> events = events(userId);
        return events.isEmpty() ? 0 : events.getLast().get("version").asLong();
    }

    private static List<String> permissions(JsonNode payload) {
        List<String> values = new java.util.ArrayList<>();
        payload.get("permissions").forEach(node -> values.add(node.asString()));
        return values;
    }

    private <T> T onPlatform(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), work);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
