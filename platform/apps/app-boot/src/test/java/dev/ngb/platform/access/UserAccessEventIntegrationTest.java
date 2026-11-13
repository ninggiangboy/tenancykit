package dev.ngb.platform.access;
import dev.ngb.platform.support.Actors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.access.application.auth.port.IdentityProviderPort;
import dev.ngb.platform.access.application.role.command.CreateRoleUseCase;
import dev.ngb.platform.access.application.role.command.ManageRoleUseCase;
import dev.ngb.platform.access.application.tenant.command.ProvisionTenantUseCase;
import dev.ngb.platform.access.application.user.command.CreateUserUseCase;
import dev.ngb.platform.access.application.user.command.ManageUserPermissionsUseCase;
import dev.ngb.platform.access.application.user.query.UserQueries;
import dev.ngb.platform.access.domain.role.Role;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import java.util.List;
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

/** Every access change publishes the affected user's full effective access with a newer version. */
@SpringBootTest(classes = Application.class)
@Import({PlatformContainerConfig.class, UserAccessEventIntegrationTest.FakeIdentities.class})
class UserAccessEventIntegrationTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired CreateTenantUseCase createTenant;
    @Autowired CreateUserUseCase createUser;
    @Autowired CreateRoleUseCase createRole;
    @Autowired ManageRoleUseCase manageRole;
    @Autowired ManageUserPermissionsUseCase managePermissions;
    @Autowired ProvisionTenantUseCase provisionTenant;
    @Autowired UserQueries queries;
    @Autowired TenantContext tenantContext;
    @Autowired OwnerDatabase owner;

    @Test
    void assigningAndEditingARoleRepublishesEveryAffectedUser() {
        Tenant tenant = onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(
                "acc" + suffix(), "Access", "user/tester", "req-1")));
        User first = newUser(tenant);
        User second = newUser(tenant);
        Role role = onPlatform(() -> createRole.execute(Actors.PLATFORM, new CreateRoleUseCase.Command(
                tenant.id(), PermissionScope.TENANT, "Reader", Set.of("billing:invoice:read"))));

        onPlatform(() -> manageRole.execute(Actors.PLATFORM, new ManageRoleUseCase.Command(
                role.id(), ManageRoleUseCase.Action.ASSIGN, Set.of(), null, first.id(), ExpectedVersion.any())));
        onPlatform(() -> manageRole.execute(Actors.PLATFORM, new ManageRoleUseCase.Command(
                role.id(), ManageRoleUseCase.Action.ASSIGN, Set.of(), null, second.id(), ExpectedVersion.any())));

        assertThat(events(first.id().value())).isNotEmpty().last().satisfies(payload -> {
            assertThat(payload.get("version").asLong()).isGreaterThan(1);
            assertThat(permissions(payload)).containsExactly("billing:invoice:read");
        });

        onPlatform(() -> manageRole.execute(Actors.PLATFORM, new ManageRoleUseCase.Command(
                role.id(), ManageRoleUseCase.Action.REPLACE_PERMISSIONS,
                Set.of("billing:invoice:read", "billing:wallet:read"), null, null, ExpectedVersion.any())));

        assertThat(events(first.id().value())).last().satisfies(payload ->
                assertThat(permissions(payload)).containsExactly("billing:invoice:read", "billing:wallet:read"));
        assertThat(events(second.id().value())).last().satisfies(payload ->
                assertThat(permissions(payload)).containsExactly("billing:invoice:read", "billing:wallet:read"));
        assertThat(events(second.id().value()).getLast().get("version").asLong())
                .isGreaterThan(events(second.id().value()).get(events(second.id().value()).size() - 2).get("version").asLong());
    }

    @Test
    void aDirectGrantPublishesTheNewAccessToo() {
        Tenant tenant = onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(
                "dir" + suffix(), "Direct", "user/tester", "req-1")));
        User user = newUser(tenant);

        onPlatform(() -> managePermissions.execute(Actors.PLATFORM, new ManageUserPermissionsUseCase.Command(
                user.id(), "billing:wallet:read", true, ExpectedVersion.any())));

        assertThat(events(user.id().value())).last().satisfies(payload ->
                assertThat(permissions(payload)).containsExactly("billing:wallet:read"));
        assertThat(onPlatform(() -> queries.list(tenant.id().value()))).hasSize(1);
    }

    @Test
    void aTenantActorCannotCreateAPlatformScopeRole() {
        Tenant tenant = onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(
                "esc" + suffix(), "Escalation", "user/tester", "req-1")));
        Actor tenantActor = new Actor(
                ActorType.USER, UUID.randomUUID(), tenant.id(), PermissionScope.TENANT, "tenant-admin", Set.of("platform:role:manage"));

        assertThatThrownBy(() -> createRole.execute(tenantActor, new CreateRoleUseCase.Command(
                tenant.id(), PermissionScope.PLATFORM, "Escalation", Set.of("platform:entitlement:manage"))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void provisioningCreatesTheTenantItsAdministratorAndTheAdministratorRole() {
        var provisioned = onPlatform(() -> provisionTenant.execute(new ProvisionTenantUseCase.Command(
                "prv" + suffix(), "Provisioned", "admin@example.test", "change-me")));

        assertThat(owner.count("select count(*) from tenants where id = ?", provisioned.tenantId().value())).isEqualTo(1);
        assertThat(owner.count(
                "select count(*) from roles where tenant_id = ? and name = 'Tenant Administrator'",
                provisioned.tenantId().value())).isEqualTo(1);
        List<JsonNode> administered = owner.jdbc().query(
                "select payload::text from outbox where type = 'user-access.changed' and payload ->> 'tenant_id' = ? order by created_at, id",
                (rs, rowNum) -> json.readTree(rs.getString(1)),
                provisioned.tenantId().value().toString());
        assertThat(administered).isNotEmpty();
        assertThat(permissions(administered.getLast())).contains("billing:invoice:read", "platform:user:manage");
    }

    private User newUser(Tenant tenant) {
        String email = "user-" + suffix() + "@example.test";
        return onPlatform(() -> createUser.execute(Actors.PLATFORM, new CreateUserUseCase.Command(
                tenant.code().value(), tenant.id(), PermissionScope.TENANT, email, "User " + email, "change-me")));
    }

    private List<JsonNode> events(UUID userId) {
        return owner.jdbc().query(
                "select payload::text from outbox where type = 'user-access.changed' and payload ->> 'user_id' = ? order by created_at, id",
                (rs, rowNum) -> json.readTree(rs.getString(1)),
                userId.toString());
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
        IdentityProviderPort fakeIdentityProvider() {
            return new IdentityProviderPort() {
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
