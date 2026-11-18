package dev.ngb.mailer.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container shared by every integration test in the JVM; the image matches docker-compose.yml.
 *
 * <p>Mailer's isolation guarantee is row-level security applied to an unprivileged role, and a superuser bypasses it.
 * So migrations run as the container's superuser — which is what a deployment's table owner is — and the application
 * connects as {@code mailer_app}, the role the migrations create. A test that connects as the superuser would leave
 * the guarantee unverified, so there is deliberately no way to ask for that here.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

    public static final String IMAGE = "postgres:18";

    /** The password the migration assigns to the unprivileged role; the application connects with it. */
    public static final String APP_ROLE = "mailer_app";
    public static final String APP_PASSWORD = "mailer_app_test_password";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE);

    static {
        POSTGRES.start();
    }

    /** Shared with tests that build their own property source instead of importing this configuration. */
    public static PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    @Bean
    DynamicPropertyRegistrar databaseProperties() {
        return registry -> {
            // The application: the unprivileged role, so row-level security actually applies.
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", () -> APP_ROLE);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            // Flyway: the same container's owner, which creates the role and owns the tables.
            registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
            registry.add("spring.flyway.user", POSTGRES::getUsername);
            registry.add("spring.flyway.password", POSTGRES::getPassword);
            registry.add("spring.flyway.placeholders.appDbPassword", () -> APP_PASSWORD);
            // Many cached Spring contexts share one container; small idle pools keep them under its connection limit.
            registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
            registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
            // Tests drive the job and the consumer directly and deterministically.
            registry.add("mailer.delivery.enabled", () -> "false");
            registry.add("mailer.jobs.enabled", () -> "false");
            registry.add("mailer.housekeeping.enabled", () -> "false");
            registry.add("mailer.credentials.health-enabled", () -> "false");
            // A fixed 32-byte master key, so the cipher can be built without a secret in the environment.
            registry.add("mailer.secrets.master-key", () -> MASTER_KEY);
        };
    }

    /** Base64 of the 32 ASCII bytes {@code 0123456789abcdef0123456789abcdef}; only ever used by tests. */
    public static final String MASTER_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
}
