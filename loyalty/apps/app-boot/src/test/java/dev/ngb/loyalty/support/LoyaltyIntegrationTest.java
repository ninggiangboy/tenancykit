package dev.ngb.loyalty.support;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.Application;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The integration harness: the real application context, the real migrations over one shared PostgreSQL container and
 * the broker wiring the api runtime expects.
 *
 * <p>Subclasses run work as a tenant through {@link #inTenant}, which binds the scope row-level security reads. Every
 * test uses fresh random tenant ids, so no test has to clean up after another.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
public abstract class LoyaltyIntegrationTest {

    /** A 32-byte key so the signing-secret cipher can start; the value itself is irrelevant to a test. */
    public static final String MASTER_KEY = "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=";

    @Autowired
    protected TenantContext tenantContext;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        PostgresContainerConfig.register(registry);
        RabbitContainerConfig.register(registry);
        registry.add("loyalty.webhooks.master-key", () -> MASTER_KEY);
        // A local test receiver is not https and lives on a loopback address, which is exactly what the compose profile
        // allows; the shipped default stays false and a contract test proves it.
        registry.add("loyalty.webhooks.allow-non-public-targets", () -> "true");
    }

    /** Runs work with the tenant bound, which is what row-level security reads. */
    protected <T> T inTenant(TenantId tenant, Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(tenant), work);
    }

    protected void inTenant(TenantId tenant, Runnable work) {
        tenantContext.runAs(RequestScope.tenant(tenant), work);
    }
}
