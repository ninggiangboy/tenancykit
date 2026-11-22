package dev.ngb.platform.access;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.Application;
import dev.ngb.platform.access.application.user.command.CreateUserUseCase;
import dev.ngb.platform.access.application.user.command.ManageUserPermissionsUseCase;
import dev.ngb.platform.access.application.user.command.ManageUserUseCase;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.support.Actors;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Every user command records exactly one audit entry, against the acting tenant. */
@SpringBootTest(classes = Application.class)
@Import({PlatformContainerConfig.class, UserCommandAuditIntegrationTest.FakeIdentities.class})
class UserCommandAuditIntegrationTest {

    private static final String CODE = "billing:wallet:read";

    @Autowired CreateTenantUseCase createTenant;
    @Autowired CreateUserUseCase createUser;
    @Autowired ManageUserUseCase manageUser;
    @Autowired ManageUserPermissionsUseCase managePermissions;
    @Autowired TenantContext tenantContext;
    @Autowired OwnerDatabase owner;

    @Test
    void everyUserCommandRecordsExactlyOneEntry() {
        User user = newUser(newTenant());
        assertThat(entries("user.created", "user/" + user.id())).isEqualTo(1);

        onPlatform(() -> manageUser.execute(Actors.PLATFORM, "req-2", new ManageUserUseCase.Command(
                user.id(), ManageUserUseCase.Action.RENAME, "Renamed", ExpectedVersion.any())));
        assertThat(entries("user.renamed", "user/" + user.id())).isEqualTo(1);

        onPlatform(() -> manageUser.execute(Actors.PLATFORM, "req-3", new ManageUserUseCase.Command(
                user.id(), ManageUserUseCase.Action.DISABLE, null, ExpectedVersion.any())));
        assertThat(entries("user.disabled", "user/" + user.id())).isEqualTo(1);

        onPlatform(() -> manageUser.execute(Actors.PLATFORM, "req-4", new ManageUserUseCase.Command(
                user.id(), ManageUserUseCase.Action.ENABLE, null, ExpectedVersion.any())));
        assertThat(entries("user.enabled", "user/" + user.id())).isEqualTo(1);

        onPlatform(() -> managePermissions.execute(Actors.PLATFORM, "req-5", new ManageUserPermissionsUseCase.Command(
                user.id(), CODE, true, ExpectedVersion.any())));
        assertThat(entries("user.permission-granted", "user/" + user.id() + "/permission/" + CODE)).isEqualTo(1);

        onPlatform(() -> managePermissions.execute(Actors.PLATFORM, "req-6", new ManageUserPermissionsUseCase.Command(
                user.id(), CODE, false, ExpectedVersion.any())));
        assertThat(entries("user.permission-revoked", "user/" + user.id() + "/permission/" + CODE)).isEqualTo(1);
    }

    private long entries(String action, String target) {
        return owner.count(
                "select count(*) from audit_log where action = ? and subject = ? and actor = ? and tenant_id = ?",
                action, target, Actors.PLATFORM.auditName(), RequestScope.PLATFORM_TENANT_ID.value());
    }

    private Tenant newTenant() {
        String code = "uad" + suffix();
        return onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(code, "User audit " + code, "user/tester", "req-1")));
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
