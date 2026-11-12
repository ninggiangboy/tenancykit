package dev.ngb.platform.access;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.runtime.UnitOfWork;
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
import dev.ngb.platform.support.ReplicaHarness;
import dev.ngb.platform.support.UncommittedRace;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two concurrent access changes on one user must publish two distinct versions, each holding the state that change
 * committed. Under the old unlocked bump both wrote the same version, and the replica dropped the second event, which
 * could keep a permission that was revoked.
 */
@SpringBootTest(classes = Application.class)
@Import({PlatformContainerConfig.class, ConcurrentUserAccessIntegrationTest.FakeIdentities.class})
class ConcurrentUserAccessIntegrationTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired CreateTenantUseCase createTenant;
    @Autowired CreateUserUseCase createUser;
    @Autowired CreateRoleUseCase createRole;
    @Autowired ManageRoleUseCase manageRole;
    @Autowired TenantContext tenantContext;
    @Autowired UnitOfWork unitOfWork;
    @Autowired OwnerDatabase owner;

    @Test
    void twoConcurrentAccessChangesOnOneUserPublishTwoDistinctVersions() throws Exception {
        Tenant tenant = newTenant();
        User user = newUser(tenant);
        Role readers = role(tenant, "Reader-" + suffix(), Set.of("billing:invoice:read"));
        Role writers = role(tenant, "Writer-" + suffix(), Set.of("billing:wallet:read"));
        long before = latestVersion(user.id().value());

        UncommittedRace.run(
                unitOfWork, tenantContext, RequestScope.platform(), owner, "%for update%",
                () -> assign(readers, user), () -> assign(writers, user));

        List<JsonNode> events = eventsSince(user.id().value(), before);
        assertThat(events).as("each committed change publishes one event").hasSize(2);
        assertThat(events.stream().map(payload -> payload.get("version").asLong()).toList())
                .as("no two events for one row may share a version")
                .doesNotHaveDuplicates();
        assertThat(permissions(events.getLast()))
                .as("the newer version holds both permissions")
                .containsExactlyInAnyOrder("billing:invoice:read", "billing:wallet:read");

        assertReplicaAgrees(user.id().value(), tenant.id().value(), events);
    }

    @Test
    void anUnassignRacingAnAssignNeverLeavesTheRevokedPermissionInTheReplica() throws Exception {
        Tenant tenant = newTenant();
        User user = newUser(tenant);
        Role readers = role(tenant, "Reader-" + suffix(), Set.of("billing:invoice:read"));
        Role writers = role(tenant, "Writer-" + suffix(), Set.of("billing:wallet:read"));
        onPlatform(() -> assign(readers, user));
        long before = latestVersion(user.id().value());

        UncommittedRace.run(
                unitOfWork, tenantContext, RequestScope.platform(), owner, "%for update%",
                () -> unassign(readers, user), () -> assign(writers, user));

        List<JsonNode> events = eventsSince(user.id().value(), before);
        assertThat(events).hasSize(2);
        assertThat(events.stream().map(payload -> payload.get("version").asLong()).toList()).doesNotHaveDuplicates();
        assertThat(permissions(events.getLast()))
                .as("the revoked permission is gone from the final state")
                .containsExactly("billing:wallet:read");

        assertReplicaAgrees(user.id().value(), tenant.id().value(), events);
    }

    /** Applies the events to a replica in both orders; the final grant is the newest event's permission set either way. */
    @SuppressWarnings("unchecked")
    private void assertReplicaAgrees(UUID userId, UUID tenantId, List<JsonNode> ascending) {
        try (ReplicaHarness replica = ReplicaHarness.create()) {
            replica.tenant(Map.of(
                    "id", tenantId.toString(),
                    "code", "replica",
                    "name", "Replica",
                    "status", "ACTIVE",
                    "created_at", "2026-01-01T00:00:00Z",
                    "version", 1));
            for (List<JsonNode> order : List.of(ascending.reversed(), ascending)) {
                for (JsonNode event : order) {
                    replica.apply("user-access.changed", json.convertValue(event, Map.class));
                }
                assertThat(replica.grantedPermissions(userId))
                        .as("delivery order must not change the final replica state")
                        .containsExactlyInAnyOrderElementsOf(permissions(ascending.getLast()));
            }
        }
    }

    private Role assign(Role role, User user) {
        return manageRole.execute(new ManageRoleUseCase.Command(
                role.id(), ManageRoleUseCase.Action.ASSIGN, Set.of(), null, user.id()));
    }

    private Role unassign(Role role, User user) {
        return manageRole.execute(new ManageRoleUseCase.Command(
                role.id(), ManageRoleUseCase.Action.UNASSIGN, Set.of(), null, user.id()));
    }

    private Tenant newTenant() {
        return onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(
                "con" + suffix(), "Concurrent", "user/tester", "req-1")));
    }

    private User newUser(Tenant tenant) {
        String email = "user-" + suffix() + "@example.test";
        return onPlatform(() -> createUser.execute(new CreateUserUseCase.Command(
                tenant.code().value(), tenant.id(), PermissionScope.TENANT, email, "User", "change-me")));
    }

    private Role role(Tenant tenant, String name, Set<String> permissions) {
        return onPlatform(() -> createRole.execute(new CreateRoleUseCase.Command(
                tenant.id(), PermissionScope.TENANT, name, permissions)));
    }

    private List<JsonNode> events(UUID userId) {
        return owner.jdbc().query(
                "select payload::text from outbox where type = 'user-access.changed' and payload ->> 'user_id' = ?"
                        + " order by (payload ->> 'version')::bigint",
                (rs, rowNum) -> json.readTree(rs.getString(1)),
                userId.toString());
    }

    /** The events published after the setup's last one, so the assertions see only the race's own writes. */
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

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeIdentities {

        @Bean
        @Primary
        dev.ngb.platform.access.application.auth.port.IdentityProviderPort fakeIdentityProvider() {
            return new dev.ngb.platform.access.application.auth.port.IdentityProviderPort() {
                @Override
                public String createUser(String username, String email, String displayName, String temporaryPassword) {
                    return "kc-" + username;
                }

                @Override
                public void deleteUser(String keycloakSubject) {}
            };
        }
    }
}
