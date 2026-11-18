package dev.ngb.platform.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.access.application.tenant.command.ProvisionTenantUseCase;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Provisioning runs in one unit of work, so a failure anywhere in it leaves no Tenant at all: no tenant row, no
 * seeded role and no administrator. The Keycloak identity that was created before the transaction is removed again.
 */
@SpringBootTest(classes = Application.class)
@Import({PlatformContainerConfig.class, ProvisionTenantAtomicityIntegrationTest.FailingInsert.class})
class ProvisionTenantAtomicityIntegrationTest {

    @Autowired ProvisionTenantUseCase provisionTenant;
    @Autowired CreateUserCompensationIntegrationTest.RecordingIdentities identities;
    @Autowired TenantContext tenantContext;
    @Autowired OwnerDatabase owner;

    @Test
    void aFailedProvisioningLeavesNoTenantAndRemovesTheIdentity() {
        String code = "pot" + suffix();
        String email = "admin-" + suffix() + "@example.test";

        assertThatThrownBy(() -> onPlatform(() -> provisionTenant.execute(
                new ProvisionTenantUseCase.Command(code, "Atomic " + code, email, "change-me"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the user insert failed");

        assertThat(owner.count("select count(*) from tenants where code = ?", code))
                .as("the tenant is rolled back with everything the provisioning wrote for it")
                .isZero();
        assertThat(owner.count("select count(*) from users where email = ?", email)).isZero();
        assertThat(identities.deleted()).containsExactly("kc-" + code + ":" + email);
    }

    private <T> T onPlatform(java.util.function.Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), work);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingInsert {

        @Bean
        @Primary
        CreateUserCompensationIntegrationTest.RecordingIdentities recordingIdentities() {
            return new CreateUserCompensationIntegrationTest.RecordingIdentities();
        }

        @Bean
        @Primary
        UserRepository failingUserRepository() {
            return new CreateUserCompensationIntegrationTest.FailingUserRepository();
        }
    }
}
