package dev.ngb.loyalty.boot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.loyalty.support.PostgresContainerConfig;
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
 * {@code loyalty_app}, the unprivileged role a deployment uses. A superuser would bypass every policy and leave the
 * promise unverified.
 *
 * <p>Every tenant-scoped table of the app is a case here: the consumer's inbox, the outbox, the three webhook tables and
 * the program, its tiers and its promotions.
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
        executeAsOwner(OUTBOX.formatted(tenant));
        executeAsOwner(ENDPOINT.formatted(tenant));
        executeAsOwner(PROGRAM.formatted(tenant));
        executeAsOwner(TIER.formatted(tenant));
        executeAsOwner(PROMOTION.formatted(tenant));

        try (Connection app = appRole()) {
            assertThat(count(app, "event_inbox", null)).isZero();
            assertThat(count(app, "loyalty_programs", null)).isZero();
            assertThat(count(app, "tiers", null)).isZero();
            assertThat(count(app, "promotions", null)).isZero();
            assertThat(count(app, "outbox", null)).isZero();
            assertThat(count(app, "webhook_endpoints", null)).isZero();
            assertThat(count(app, "webhook_deliveries", null)).isZero();
            assertThat(count(app, "webhook_delivery_attempts", null)).isZero();
        }
    }

    @Test
    void oneTenantSeesOnlyItsOwnRowsAndCannotWriteAnothers() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        executeAsOwner(ENDPOINT.formatted(tenantA));
        executeAsOwner(ENDPOINT.formatted(tenantB));

        try (Connection app = appRole()) {
            assertThat(count(app, "webhook_endpoints", tenantA)).isEqualTo(1);
            assertThat(count(app, "webhook_endpoints", tenantB)).isEqualTo(1);

            assertThatThrownBy(() -> insertEndpointAs(app, tenantA, tenantB))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    /**
     * One row per tenant table, inserted as the owner. The case is the same for every table: the app role sees nothing
     * until a tenant is bound, and exactly one row once it is. The pattern is the migration's {@code enable_tenant_rls}
     * call, so a table that forgot it fails here.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantTables")
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

    static Stream<Arguments> tenantTables() {
        return Stream.of(
                Arguments.of("event_inbox", """
                        insert into event_inbox (tenant_id, consumer, event_id, processed_at)
                        values ('%1$s', 'replica', gen_random_uuid(), now())
                        """),
                Arguments.of("outbox", OUTBOX),
                Arguments.of("webhook_endpoints", ENDPOINT),
                Arguments.of("webhook_deliveries", DELIVERY),
                Arguments.of("webhook_delivery_attempts", ATTEMPT),
                Arguments.of("loyalty_programs", PROGRAM),
                Arguments.of("tiers", TIER),
                Arguments.of("promotions", PROMOTION));
    }

    // ------------------------------------------------------------------ helpers

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

    private static void executeAsOwner(String sql) {
        try (Connection owner = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = owner.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed a row", e);
        }
    }

    private static void insertEndpointAs(Connection app, UUID bound, UUID target) throws SQLException {
        bind(app, bound);
        try (Statement statement = app.createStatement()) {
            statement.execute(ENDPOINT.formatted(target));
        }
    }

    private static final String ENDPOINT = """
            insert into webhook_endpoints (id, tenant_id, url, event_types, status, key_id, secret_ciphertext,
                                           created_at, updated_at)
            values (gen_random_uuid(), '%1$s', 'https://hooks.example.com/x', '{member.enrolled}', 'ACTIVE', 'whk_test',
                    'v1.ciphertext', now(), now())
            """;

    private static final String DELIVERY = """
            insert into webhook_deliveries (id, tenant_id, endpoint_id, event_id, event_type, body, status,
                                            next_attempt_at, created_at)
            values (gen_random_uuid(), '%1$s', gen_random_uuid(), gen_random_uuid(), 'member.enrolled', '{}', 'PENDING',
                    now(), now())
            """;

    private static final String ATTEMPT = """
            with d as (
                insert into webhook_deliveries (id, tenant_id, endpoint_id, event_id, event_type, body, status,
                                                next_attempt_at, created_at)
                values (gen_random_uuid(), '%1$s', gen_random_uuid(), gen_random_uuid(), 'member.enrolled', '{}',
                        'PENDING', now(), now()) returning id
            )
            insert into webhook_delivery_attempts (id, tenant_id, delivery_id, attempt_no, latency_ms, attempted_at)
            select gen_random_uuid(), '%1$s', d.id, 1, 5, now() from d
            """;

    private static final String PROGRAM = """
            insert into loyalty_programs (tenant_id, currency, unit_amount, created_at, updated_at)
            values ('%1$s', 'USD', 1000, now(), now())
            """;

    private static final String TIER = """
            insert into tiers (tenant_id, id, code, name, min_spend, earn_multiplier, position)
            values ('%1$s', gen_random_uuid(), 'BRONZE', 'Bronze', 0, 1.00, 0)
            """;

    private static final String PROMOTION = """
            insert into promotions (tenant_id, id, name, status, multiplier, created_at, updated_at)
            values ('%1$s', gen_random_uuid(), 'Double points', 'ACTIVE', 2.00, now(), now())
            """;

    private static final String OUTBOX = """
            insert into outbox (id, tenant_id, aggregate, type, payload, created_at, next_attempt_at)
            values (gen_random_uuid(), '%1$s', 'member/1', 'points.earned', '{}', now(), now())
            """;
}
