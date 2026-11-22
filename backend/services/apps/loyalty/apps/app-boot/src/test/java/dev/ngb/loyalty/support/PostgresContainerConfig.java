package dev.ngb.loyalty.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container shared by every integration test in the JVM; the image matches docker-compose.yml.
 *
 * <p>loyalty's isolation guarantee is row-level security applied to an unprivileged role, and a superuser bypasses it.
 * So migrations run as the container's superuser — which is what a deployment's table owner is — and the application
 * connects as {@code loyalty_app}, the role the migrations create.
 */
public final class PostgresContainerConfig {

    public static final String IMAGE = "postgres:18";

    /** The password the migration assigns to the unprivileged role; the application connects with it. */
    public static final String APP_ROLE = "loyalty_app";

    public static final String APP_PASSWORD = "loyalty_app_test_password";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE);

    static {
        POSTGRES.start();
    }

    private PostgresContainerConfig() {}

    public static PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    /** The application's own properties: the unprivileged role for the runtime, the owner for Flyway. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.appDbPassword", () -> APP_PASSWORD);
        // The api profile ships with migrations off, because a deployment runs the migrate runtime before it. A test
        // has no such runtime in front of it, so each context migrates its own throwaway container first: the schema
        // exists whatever order the classes run in, and the unprivileged role exists before jOOQ reads the dialect.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        // Tests drive the jobs and the consumer directly and deterministically.
        registry.add("loyalty.jobs.enabled", () -> "false");
        registry.add("loyalty.housekeeping.enabled", () -> "false");
        // No Platform runs during a test; the replica is seeded by the test that needs it.
        registry.add("suite.replica.rebuild-on-start", () -> "false");
    }

    /** A connection as the container's superuser, which is what a deployment's table owner is. */
    public static Connection asOwner() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Runs one statement as the owner, for the replica rows a test seeds. */
    public static void executeAsOwner(String sql) {
        try (Connection owner = asOwner();
                Statement statement = owner.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("could not run: " + sql, e);
        }
    }
}
