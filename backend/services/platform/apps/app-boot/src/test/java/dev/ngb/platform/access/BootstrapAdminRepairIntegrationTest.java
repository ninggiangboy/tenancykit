package dev.ngb.platform.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.access.application.access.port.AccessRepository;
import dev.ngb.platform.access.application.access.service.AccessPublisher;
import dev.ngb.platform.access.application.auth.port.IdentityProviderPort;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.access.application.role.port.RoleRepository;
import dev.ngb.platform.access.application.user.command.BootstrapPlatformAdminUseCase;
import dev.ngb.platform.access.application.user.command.CreateUserUseCase;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * Bootstrapping is a repair, not a one-shot create. A boot that failed between creating the administrator and assigning
 * its role leaves a user who can sign in with no permission; the next boot must complete the assignment instead of
 * returning early because the user row is present.
 */
@SpringBootTest(classes = Application.class)
@Import({PlatformContainerConfig.class, BootstrapAdminRepairIntegrationTest.Recording.class})
class BootstrapAdminRepairIntegrationTest {

    @Autowired UnitOfWork unitOfWork;
    @Autowired CreateUserUseCase createUser;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AccessRepository access;
    @Autowired PermissionCatalog catalog;
    @Autowired AccessPublisher publisher;
    @Autowired IdGenerator ids;
    @Autowired Clock clock;
    @Autowired RecordingIdentities identities;
    @Autowired TenantContext tenantContext;
    @Autowired OwnerDatabase owner;

    @Test
    void aBootThatFailsWhileAssigningIsCompletedByTheNextBoot() {
        String email = "admin-" + suffix() + "@example.test";

        FailingAssignRepository failing = new FailingAssignRepository(access);
        BootstrapPlatformAdminUseCase broken = bootstrapUseCase(failing);

        assertThatThrownBy(() -> onPlatform(() -> {
                    broken.execute(email, "change-me");
                    return null;
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the assignment failed");

        assertThat(owner.count("select count(*) from users where email = ?", email)).isEqualTo(1);
        User admin = onPlatform(() -> unitOfWork.transactional(() -> users.findByEmail(RequestScope.PLATFORM_TENANT_ID, email)))
                .orElseThrow();
        assertThat(grantsOf(admin.id().value()))
                .as("the failed unit of work rolled back the role and the assignment")
                .isZero();
        assertThat(identities.created()).containsExactly("platform:" + email);

        BootstrapPlatformAdminUseCase repair = bootstrapUseCase(access);

        onPlatform(() -> {
            repair.execute(email, "change-me");
            return null;
        });

        assertThat(grantsOf(admin.id().value())).isEqualTo(1);
        assertThat(onPlatform(() -> unitOfWork.transactional(() -> access.effectivePermissions(admin.id().value()))))
                .isNotEmpty();

        onPlatform(() -> {
            repair.execute(email, "change-me");
            return null;
        });

        assertThat(owner.count("select count(*) from roles where name = ?", "Platform Administrator")).isEqualTo(1);
        assertThat(grantsOf(admin.id().value())).isEqualTo(1);
        assertThat(identities.created()).containsExactly("platform:" + email);
    }

    private BootstrapPlatformAdminUseCase bootstrapUseCase(AccessRepository accessRepository) {
        return new BootstrapPlatformAdminUseCase(
                unitOfWork, createUser, users, roles, accessRepository, catalog, publisher, ids, clock);
    }

    private int grantsOf(UUID userId) {
        return owner.count("select count(*) from role_assignments where user_id = ?", userId);
    }

    private <T> T onPlatform(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), work);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** Fails the assignment, which is the second transaction the old bootstrap could leave half-done. */
    static class FailingAssignRepository implements AccessRepository {

        private final AccessRepository delegate;

        FailingAssignRepository(AccessRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Set<String> effectivePermissions(UUID userId) {
            return delegate.effectivePermissions(userId);
        }

        @Override
        public List<UUID> userIdsForRole(UUID roleId) {
            return delegate.userIdsForRole(roleId);
        }

        @Override
        public void assign(UUID tenantId, UUID userId, UUID roleId) {
            throw new IllegalStateException("the assignment failed");
        }

        @Override
        public void unassign(UUID tenantId, UUID userId, UUID roleId) {
            delegate.unassign(tenantId, userId, roleId);
        }

        @Override
        public void grant(UUID tenantId, UUID userId, String permissionCode) {
            delegate.grant(tenantId, userId, permissionCode);
        }

        @Override
        public void revoke(UUID tenantId, UUID userId, String permissionCode) {
            delegate.revoke(tenantId, userId, permissionCode);
        }

        @Override
        public Set<String> directPermissions(UUID userId) {
            return delegate.directPermissions(userId);
        }
    }

    /** Records the identity a boot creates, so a repair can prove it did not create a second one. */
    static class RecordingIdentities implements IdentityProviderPort {

        private final List<String> created = new CopyOnWriteArrayList<>();

        @Override
        public String createUser(String username, String email, String displayName, String temporaryPassword) {
            created.add(username);
            return "kc-" + username;
        }

        @Override
        public void deleteUser(String keycloakSubject) {
            created.remove(keycloakSubject.substring("kc-".length()));
        }

        List<String> created() {
            return List.copyOf(created);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Recording {

        @Bean
        @Primary
        RecordingIdentities recordingIdentities() {
            return new RecordingIdentities();
        }
    }
}
