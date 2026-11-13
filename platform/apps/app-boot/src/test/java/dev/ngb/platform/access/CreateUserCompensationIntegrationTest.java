package dev.ngb.platform.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.access.application.auth.port.IdentityProviderPort;
import dev.ngb.platform.access.application.user.command.CreateUserUseCase;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.access.domain.user.UserId;
import dev.ngb.platform.support.Actors;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * The Keycloak call runs outside the unit of work, so a write that fails afterwards must undo the identity. A
 * created-then-deleted identity is the only trace, and no user row survives.
 */
@SpringBootTest(classes = Application.class)
@Import({PlatformContainerConfig.class, CreateUserCompensationIntegrationTest.FailingInsert.class})
class CreateUserCompensationIntegrationTest {

    @Autowired CreateTenantUseCase createTenant;
    @Autowired CreateUserUseCase createUser;
    @Autowired RecordingIdentities identities;
    @Autowired TenantContext tenantContext;
    @Autowired OwnerDatabase owner;

    @Test
    void aFailedWriteRemovesTheIdentityItCreated() {
        Tenant tenant = newTenant();
        String email = "user-" + suffix() + "@example.test";
        String subject = "kc-" + tenant.code().value() + ":" + email;

        assertThatThrownBy(() -> onPlatform(() -> createUser.execute(Actors.PLATFORM, "req-1", new CreateUserUseCase.Command(
                tenant.code().value(), tenant.id(), PermissionScope.TENANT, email, "User", "change-me"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the user insert failed");

        assertThat(identities.deleted()).containsExactly(subject);
        assertThat(owner.count("select count(*) from users where email = ?", email)).isZero();
    }

    private Tenant newTenant() {
        String code = "cmp" + suffix();
        return onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(code, "Compensation " + code, "user/tester", "req-1")));
    }

    private <T> T onPlatform(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), work);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** Records every identity removal so the test can assert the compensation happened. */
    static class RecordingIdentities implements IdentityProviderPort {

        private final List<String> deleted = new CopyOnWriteArrayList<>();

        @Override
        public String createUser(String username, String email, String displayName, String temporaryPassword) {
            return "kc-" + username;
        }

        @Override
        public void deleteUser(String keycloakSubject) {
            deleted.add(keycloakSubject);
        }

        List<String> deleted() {
            return List.copyOf(deleted);
        }
    }

    /** Fails every insert, which is the failure the compensation exists for. */
    static class FailingUserRepository implements UserRepository {

        @Override
        public void add(User user) {
            throw new IllegalStateException("the user insert failed");
        }

        @Override
        public void update(User user) {
            throw new IllegalStateException("the user insert failed");
        }

        @Override
        public Optional<User> findById(UserId id) {
            return Optional.empty();
        }

        @Override
        public Optional<User> lockById(UserId id) {
            return Optional.empty();
        }

        @Override
        public Optional<User> findByKeycloakSubject(String subject) {
            return Optional.empty();
        }

        @Override
        public Optional<User> findByEmail(TenantId tenantId, String email) {
            return Optional.empty();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingInsert {

        @Bean
        @Primary
        RecordingIdentities recordingIdentities() {
            return new RecordingIdentities();
        }

        @Bean
        @Primary
        UserRepository failingUserRepository() {
            return new FailingUserRepository();
        }
    }
}
