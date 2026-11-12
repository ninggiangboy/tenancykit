package dev.ngb.platform.support;

import dev.ngb.platform.boot.security.ServiceClients;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * One PostgreSQL container and one RabbitMQ container shared by every integration test in the JVM; the images match
 * the migration environment. Flyway connects as the container's superuser (the table owner) and the application
 * connects as the unprivileged platform_app role, exactly as in production.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PlatformContainerConfig {

    public static final String IMAGE = "postgres:18";
    public static final String RABBITMQ_IMAGE = "rabbitmq:4.1-management";
    public static final String APP_ROLE = "platform_app";
    public static final String APP_PASSWORD = "app-role-test-secret";
    public static final String CLIENT_ID = "billing";
    public static final String CLIENT_SECRET = "s3cret-the-billing-client";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE);
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(RABBITMQ_IMAGE);

    static {
        POSTGRES.start();
        RABBITMQ.start();
    }

    public static PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    public static RabbitMQContainer rabbitmq() {
        return RABBITMQ;
    }

    @org.springframework.context.annotation.Bean
    OwnerDatabase ownerDatabase() {
        return new OwnerDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @org.springframework.context.annotation.Bean
    DynamicPropertyRegistrar platformProperties() {
        String secretHash = ServiceClients.sha256(CLIENT_SECRET);
        return registry -> {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", () -> APP_ROLE);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
            registry.add("spring.flyway.user", POSTGRES::getUsername);
            registry.add("spring.flyway.password", POSTGRES::getPassword);
            registry.add("spring.flyway.placeholders.appDbPassword", () -> APP_PASSWORD);
            registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
            registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
            registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
            registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
            registry.add("spring.rabbitmq.publisher-confirm-type", () -> "simple");
            // MockMvc and local calls reach the app as "localhost", so that is the platform hostname in tests.
            registry.add("platform.tenancy.platform-hostname", () -> "localhost");
            registry.add("platform.tokens.issuer", () -> "http://platform.test");
            registry.add("platform.users.jwk-set-uri", () -> "");
            registry.add("platform.service-clients[0].id", () -> CLIENT_ID);
            registry.add("platform.service-clients[0].secret-hash", () -> secretHash);
            registry.add("platform.service-clients[0].scopes[0]", () -> "platform:replica:read");
        };
    }
}
