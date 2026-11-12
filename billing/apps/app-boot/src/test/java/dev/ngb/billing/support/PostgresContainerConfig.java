package dev.ngb.billing.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * One PostgreSQL container and one RabbitMQ container shared by every integration test in the JVM; the images
 * match docker-compose.yml. Flyway connects as the container's superuser (the table owner) and the application
 * connects as the unprivileged billing_app role, exactly as in production.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

    public static final String IMAGE = "postgres:18";
    public static final String RABBITMQ_IMAGE = "rabbitmq:4.1-management";
    public static final String APP_ROLE = "billing_app";
    public static final String APP_PASSWORD = "app-role-test-secret";
    /** 32 bytes, base64: a fixed key for tests only. */
    public static final String TEST_MASTER_KEY = "dGVzdC1tYXN0ZXIta2V5LTMyLWJ5dGVzLWxvbmctISE=";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE);
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(RABBITMQ_IMAGE);

    static {
        POSTGRES.start();
        RABBITMQ.start();
    }

    /** Shared with tests that build their own property source instead of importing this configuration. */
    public static PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    /**
     * The replica properties every test needs, for the tests that build their own registry instead of importing this
     * configuration. The replica is seeded directly, so the snapshot fetch is never reached; the properties are
     * validated at startup and so need a value.
     */
    public static void replicaDefaults(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("suite.replica.platform-base-url", () -> "http://platform.test");
        registry.add("suite.replica.client-id", () -> "billing");
        registry.add("suite.replica.client-secret", () -> "test-secret");
        registry.add("suite.replica.rebuild-on-start", () -> "false");
        registry.add("suite.app-code", () -> "billing");
    }

    @Bean
    DynamicPropertyRegistrar databaseProperties() {
        return registry -> {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", () -> APP_ROLE);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
            registry.add("spring.flyway.user", POSTGRES::getUsername);
            registry.add("spring.flyway.password", POSTGRES::getPassword);
            registry.add("spring.flyway.placeholders.appDbPassword", () -> APP_PASSWORD);
            // Many cached Spring contexts share one container; small idle pools keep them under its connection limit.
            registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
            registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
            registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
            registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
            registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
            registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
            // The broker relay marks a row only after waitForConfirmsOrDie: simple confirms, not correlated.
            registry.add("spring.rabbitmq.publisher-confirm-type", () -> "simple");
            // Tests drive the background jobs directly and deterministically.
            registry.add("billing.jobs.enabled", () -> "false");
            registry.add("billing.secrets.master-key", () -> TEST_MASTER_KEY);
            // Delivery tests use a receiver on loopback over http.
            registry.add("billing.webhooks.allow-insecure-targets", () -> "true");
            // A small budget keeps the retry-to-dead-letter tests short.
            registry.add("billing.webhooks.max-attempts", () -> "3");
            registry.add("billing.outbox.max-attempts", () -> "3");
            // Stripe events that cannot be applied give up quickly in tests.
            registry.add("billing.payments.event-max-attempts", () -> "3");
            // The replica is seeded straight into the tables; the snapshot fetch is never reached in tests, but the
            // properties are validated at startup, so they need a value here rather than in every test class.
            registry.add("suite.replica.platform-base-url", () -> "http://platform.test");
            registry.add("suite.replica.client-id", () -> "billing");
            registry.add("suite.replica.client-secret", () -> "test-secret");
            registry.add("suite.replica.rebuild-on-start", () -> "false");
        };
    }

    @Bean
    OwnerDatabase ownerDatabase() {
        return new OwnerDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
