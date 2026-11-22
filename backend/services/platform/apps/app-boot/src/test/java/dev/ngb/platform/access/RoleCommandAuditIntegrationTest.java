package dev.ngb.platform.access;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.Application;
import dev.ngb.platform.access.application.role.command.CreateRoleUseCase;
import dev.ngb.platform.access.application.role.command.ManageRoleUseCase;
import dev.ngb.platform.access.application.user.command.CreateUserUseCase;
import dev.ngb.platform.access.domain.role.Role;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.support.Actors;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
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

/** Every role command records exactly one audit entry, against the acting tenant. */
@SpringBootTest(classes = Application.class)
@Import({PlatformContainerConfig.class, RoleCommandAuditIntegrationTest.FakeIdentities.class})
class RoleCommandAuditIntegrationTest {

    @Autowired CreateTenantUseCase createTenant;
    @Autowired CreateUserUseCase createUser;
    @Autowired CreateRoleUseCase createRole;
    @Autowired ManageRoleUseCase manageRole;
    @Autowired TenantContext tenantContext;
    @Autowired OwnerDatabase owner;

    @Test
    void everyRoleCommandRecordsExactlyOneEntry() {
        Tenant tenant = newTenant();
        Role role = onPlatform(() -> createRole.execute(Actors.PLATFORM, "req-1", new CreateRoleUseCase.Command(
                tenant.id(), PermissionScope.TENANT, "Reader-" + suffix(), Set.of("billing:invoice:read"))));
        assertThat(entries("role.created", "role/" + role.id())).isEqualTo(1);

        User user = newUser(tenant);

        onPlatform(() -> manageRole.execute(Actors.PLATFORM, "req-2", new ManageRoleUseCase.Command(
                role.id(), ManageRoleUseCase.Action.RENAME, Set.of(), "Renamed-" + suffix(), null, ExpectedVersion.any())));
        assertThat(entries("role.renamed", "role/" + role.id())).isEqualTo(1);

        onPlatform(() -> manageRole.execute(Actors.PLATFORM, "req-3", new ManageRoleUseCase.Command(
                role.id(), ManageRoleUseCase.Action.REPLACE_PERMISSIONS,
                Set.of("billing:invoice:read", "billing:wallet:read"), null, null, ExpectedVersion.any())));
        assertThat(entries("role.permissions-replaced", "role/" + role.id())).isEqualTo(1);

        onPlatform(() -> manageRole.execute(Actors.PLATFORM, "req-4", new ManageRoleUseCase.Command(
                role.id(), ManageRoleUseCase.Action.ASSIGN, Set.of(), null, user.id(), ExpectedVersion.any())));
        assertThat(entries("role.assigned", "role/" + role.id() + "/user/" + user.id())).isEqualTo(1);

        onPlatform(() -> manageRole.execute(Actors.PLATFORM, "req-5", new ManageRoleUseCase.Command(
                role.id(), ManageRoleUseCase.Action.UNASSIGN, Set.of(), null, user.id(), ExpectedVersion.any())));
        assertThat(entries("role.unassigned", "role/" + role.id() + "/user/" + user.id())).isEqualTo(1);
    }

    private long entries(String action, String target) {
        return owner.count(
                "select count(*) from audit_log where action = ? and subject = ? and actor = ? and tenant_id = ?",
                action, target, Actors.PLATFORM.auditName(), RequestScope.PLATFORM_TENANT_ID.value());
    }

    private Tenant newTenant() {
        String code = "rad" + suffix();
        return onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(code, "Role audit " + code, "user/tester", "req-1")));
    }

    private User newUser(Tenant tenant) {
        String email = "user-" + suffix() + "@example.test";
        return onPlatform(() -> createUser.execute(Actors.PLATFORM, "req-1", new CreateUserUseCase.Command(
                tenant.code().value(), tenant.id(), PermissionScope.TENANT, email, "User", "change-me")));
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
                public String createUser(
                        String username, String email, String displayName, String temporaryPassword, dev.ngb.core.id.TenantId tenantId) {
                    return "kc-" + username;
                }

                @Override
                public void deleteUser(String keycloakSubject) {}
            };
        }
    }
}
