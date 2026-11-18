package dev.ngb.mailroom.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container shared by every integration test in the JVM; the image matches docker-compose.yml.
 *
 * <p>Email's isolation guarantee is row-level security applied to an unprivileged role, and a superuser
 * bypasses it. So migrations run as the container's superuser — which is what a deployment's table owner is — and the
 * application connects as {@code mailroom_app}, the role the migrations create.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

    public static final String IMAGE = "postgres:18";

    /** The password the migration assigns to the unprivileged role; the application connects with it. */
    public static final String APP_ROLE = "mailroom_app";

    public static final String APP_PASSWORD = "mailroom_app_test_password";

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
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", () -> APP_ROLE);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
            registry.add("spring.flyway.user", POSTGRES::getUsername);
            registry.add("spring.flyway.password", POSTGRES::getPassword);
            registry.add("spring.flyway.placeholders.appDbPassword", () -> APP_PASSWORD);
            registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
            registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
            // Tests drive the job and the consumer directly and deterministically.
            registry.add("mailroom.jobs.enabled", () -> "false");
            registry.add("mailroom.housekeeping.enabled", () -> "false");
            // No Platform runs during a test; the replica is seeded by the test that needs it.
            registry.add("suite.replica.rebuild-on-start", () -> "false");
        };
    }
}
