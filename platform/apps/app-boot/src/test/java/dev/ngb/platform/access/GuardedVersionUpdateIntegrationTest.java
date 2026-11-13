package dev.ngb.platform.access;
import dev.ngb.platform.support.Actors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.access.application.user.command.CreateUserUseCase;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.domain.user.User;
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

/**
 * The repository update refuses a version that is not newer. It is defence in depth: an unlocked caller fails loudly
 * here instead of publishing an event the replica drops as a duplicate.
 */
@SpringBootTest(classes = Application.class)
@Import({PlatformContainerConfig.class, GuardedVersionUpdateIntegrationTest.FakeIdentities.class})
class GuardedVersionUpdateIntegrationTest {

    @Autowired CreateTenantUseCase createTenant;
    @Autowired CreateUserUseCase createUser;
    @Autowired UserRepository users;
    @Autowired TenantContext tenantContext;
    @Autowired UnitOfWork unitOfWork;

    @Test
    void writingTheSameVersionAgainIsRefused() {
        Tenant tenant = newTenant();
        User created = newUser(tenant);

        // The first write carries version 2 and is accepted.
        onPlatform(() -> unitOfWork.transactional(() -> {
            User locked = users.lockById(created.id()).orElseThrow();
            locked.rename("Renamed");
            users.update(locked);
            return locked;
        }));

        // A second writer that did not see the first one's bump carries the same version; it must fail, not overwrite.
        assertThatThrownBy(() -> onPlatform(() -> unitOfWork.transactional(() -> {
            User stale = User.restore(
                    created.id(), created.tenantId(), created.scope(), created.keycloakSubject(), created.email(),
                    "Another name", created.status(), created.createdAt(), created.version() + 1);
            users.update(stale);
            return stale;
        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not newer");

        assertThat(onPlatform(() ->
                unitOfWork.transactional(() -> users.findById(created.id())).orElseThrow().displayName()))
                .isEqualTo("Renamed");
    }

    private Tenant newTenant() {
        String code = "gd" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(code, "Guarded " + code, "user/tester", "req-1")));
    }

    private User newUser(Tenant tenant) {
        String email = "user-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@example.test";
        return onPlatform(() -> createUser.execute(Actors.PLATFORM, new CreateUserUseCase.Command(
                tenant.code().value(), tenant.id(), PermissionScope.TENANT, email, "User", "change-me")));
    }

    private <T> T onPlatform(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), work);
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
