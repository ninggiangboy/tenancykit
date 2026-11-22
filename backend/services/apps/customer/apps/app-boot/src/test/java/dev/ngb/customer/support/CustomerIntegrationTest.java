package dev.ngb.customer.support;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.customer.Application;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * customer's integration harness: the real application context, the real migrations over one shared PostgreSQL container,
 * and the broker wiring the api runtime expects.
 *
 * <p>Subclasses run work as a tenant through {@link #inTenant}, which binds the scope row-level security reads. Every
 * test uses fresh random tenant ids, so no test has to clean up after another.
 *
 * <p>{@code allow-non-public-targets} is on here for the same reason a deployment turns it on in compose: a test
 * receiver is a local HTTP server, not a public https endpoint. The production default is asserted separately by
 * {@code SettingsDefaultsTest}.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
public abstract class CustomerIntegrationTest {

    /** A base64 32-byte key; the secret cipher refuses to start without one, so the harness supplies its own. */
    public static final String MASTER_KEY = "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=";

    @Autowired
    protected TenantContext tenantContext;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        var postgres = PostgresContainerConfig.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresContainerConfig.APP_ROLE);
        registry.add("spring.datasource.password", () -> PostgresContainerConfig.APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.placeholders.appDbPassword", () -> PostgresContainerConfig.APP_PASSWORD);
        var rabbit = RabbitContainerConfig.rabbit();
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("suite.replica.rebuild-on-start", () -> "false");
        registry.add("customer.jobs.enabled", () -> "false");
        registry.add("customer.housekeeping.enabled", () -> "false");
        registry.add("customer.webhooks.master-key", () -> MASTER_KEY);
        registry.add("customer.webhooks.allow-non-public-targets", () -> "true");
    }

    /** Runs work with the tenant bound, which is what row-level security reads. */
    protected <T> T inTenant(TenantId tenant, Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(tenant), work);
    }

    protected void inTenant(TenantId tenant, Runnable work) {
        tenantContext.runAs(RequestScope.tenant(tenant), work);
    }
}
