package dev.ngb.booking.boot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.booking.support.PostgresContainerConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The isolation guarantee, proved rather than asserted: the migrations run as the owner and every assertion connects as
 * {@code booking_app}, the unprivileged role a deployment uses. A superuser would bypass every policy and leave the
 * promise unverified.
 *
 * <p>Every tenant-scoped table the baseline creates has a case here. A table added without a case is a table whose
 * policy nobody checked, so each phase that adds one adds its case.
 */
class RowLevelSecurityTest {

    private static final PostgreSQLContainer POSTGRES = PostgresContainerConfig.postgres();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("appDbPassword", PostgresContainerConfig.APP_PASSWORD))
                .load()
                .migrate();
    }

    @Test
    void theAppRoleCannotSeeAnythingBeforeATenantIsBound() throws SQLException {
        UUID tenant = UUID.randomUUID();
        executeAsOwner(settingsInsert(tenant));

        try (Connection app = appRole()) {
            assertThat(count(app, "booking_settings", null)).isZero();
            assertThat(count(app, "outbox", null)).isZero();
            assertThat(count(app, "event_inbox", null)).isZero();
        }
    }

    @Test
    void oneTenantSeesOnlyItsOwnRowsAndCannotWriteAnothers() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        executeAsOwner(settingsInsert(tenantA));
        executeAsOwner(settingsInsert(tenantB));

        try (Connection app = appRole()) {
            assertThat(count(app, "booking_settings", tenantA)).isEqualTo(1);
            assertThat(count(app, "booking_settings", tenantB)).isEqualTo(1);

            // The helper wraps the driver's exception, so the cause is what names the policy that refused the write.
            assertThatThrownBy(() -> insertSettingsAs(app, tenantA, tenantB))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseInstanceOf(SQLException.class)
                    .hasStackTraceContaining("row-level security");
        }
    }

    /**
     * One row per tenant table, inserted as the owner. The case is the same for every table: the app role sees nothing
     * until a tenant is bound, and exactly one row once it is. The pattern is the migration's {@code enable_tenant_rls}
     * call, so a table that forgot it fails here.
     */
    @ParameterizedTest
    @MethodSource("tenantTableRows")
    void everyTenantTableIsIsolated(String table, String insert) throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        executeAsOwner(insert.formatted(tenantA));
        executeAsOwner(insert.formatted(tenantB));

        try (Connection app = appRole()) {
            assertThat(count(app, table, null)).as(table + " is visible before a tenant is bound").isZero();
            assertThat(count(app, table, tenantA)).as(table + " hides tenant A from its owner").isEqualTo(1);
            assertThat(count(app, table, tenantB)).as(table + " hides tenant B from its owner").isEqualTo(1);
        }
    }

    static Stream<Arguments> tenantTableRows() {
        return Stream.of(
                Arguments.of("booking_settings", settingsInsert("%1$s")),
                Arguments.of("outbox", outboxInsert("%1$s")),
                Arguments.of("event_inbox", """
                        insert into event_inbox (tenant_id, consumer, event_id) values ('%1$s', 'booking', gen_random_uuid())
                        """));
    }

    private static String settingsInsert(Object tenant) {
        return """
                insert into booking_settings (tenant_id, hold_ttl_seconds) values ('%1$s', 600)
                """
                .formatted(tenant);
    }

    private static String outboxInsert(String tenant) {
        return """
                insert into outbox (id, tenant_id, aggregate, type, payload, created_at, next_attempt_at)
                values (gen_random_uuid(), '%1$s', 'booking/x', 'booking.held', '{}'::jsonb, now(), now())
                """
                .formatted(tenant);
    }

    private static void executeAsOwner(String sql) {
        try (Connection owner = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = owner.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed a row", e);
        }
    }

    private static Connection appRole() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), PostgresContainerConfig.APP_ROLE, PostgresContainerConfig.APP_PASSWORD);
    }

    private static void bind(Connection connection, UUID tenant) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Session-local, not transaction-local: this connection runs each statement in its own transaction, so a
            // transaction-local setting would be gone before the statement that needs it.
            statement.execute("select set_config('app.tenant_id', '" + tenant + "', false)");
        }
    }

    private static int count(Connection connection, String table, UUID tenant) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (tenant != null) {
                bind(connection, tenant);
            }
            try (ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static void insertSettingsAs(Connection connection, UUID scope, UUID rowTenant) {
        try (Statement statement = connection.createStatement()) {
            bind(connection, scope);
            statement.execute(
                    "insert into booking_settings (tenant_id, hold_ttl_seconds) values ('" + rowTenant + "', 600)");
        } catch (SQLException e) {
            throw new IllegalStateException("could not write across tenants", e);
        }
    }
}
