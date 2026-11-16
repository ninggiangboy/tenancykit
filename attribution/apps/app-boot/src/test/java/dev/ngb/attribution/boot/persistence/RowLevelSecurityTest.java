package dev.ngb.attribution.boot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.attribution.support.PostgresContainerConfig;
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
 * {@code attribution_app}, the unprivileged role a deployment uses. A superuser would bypass every policy and leave the
 * promise unverified.
 *
 * <p>{@code attribution_settings} and {@code outbox} are the two tenant-scoped tables the skeleton creates; every later
 * phase adds one case per table it creates.
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
        insertSettingsAsOwner(tenant);
        insertOutboxAsOwner(tenant);

        try (Connection app = appRole()) {
            assertThat(count(app, "attribution_settings", null)).isZero();
            assertThat(count(app, "outbox", null)).isZero();
            assertThat(count(app, "event_inbox", null)).isZero();
        }
    }

    @Test
    void oneTenantSeesOnlyItsOwnRowsAndCannotWriteAnothers() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        insertSettingsAsOwner(tenantA);
        insertSettingsAsOwner(tenantB);

        try (Connection app = appRole()) {
            assertThat(count(app, "attribution_settings", tenantA)).isEqualTo(1);
            assertThat(count(app, "attribution_settings", tenantB)).isEqualTo(1);

            assertThatThrownBy(() -> insertSettingsAs(app, tenantA, tenantB))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
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
                row("attribution_settings", """
                        insert into attribution_settings (tenant_id, click_param) values ('%1$s', 'go')
                        """),
                row("outbox", """
                        insert into outbox (id, tenant_id, aggregate, type, payload, created_at, next_attempt_at)
                        values (gen_random_uuid(), '%1$s', 'campaign/1', 'campaign.changed', '{}', now(), now())
                        """),
                row("campaigns", """
                        insert into campaigns (id, tenant_id, name, slug, status, attribution_model, window_days, currency,
                                               created_at, updated_at)
                        values (gen_random_uuid(), '%1$s', 'Spring', 'spring-sale', 'ACTIVE', 'LAST', 30, 'USD', now(), now())
                        """),
                row("links", """
                        with c as (
                            insert into campaigns (id, tenant_id, name, slug, status, attribution_model, window_days, currency,
                                                   created_at, updated_at)
                            values (gen_random_uuid(), '%1$s', 'Spring', 'spring-sale', 'ACTIVE', 'LAST', 30, 'USD', now(), now())
                            returning id
                        )
                        insert into links (id, tenant_id, campaign_id, slug, kind, status, utm, created_at, updated_at)
                        select gen_random_uuid(), '%1$s', c.id, 'spring-link', 'STANDARD', 'ACTIVE', '{}', now(), now() from c
                        """),
                row("link_destinations", """
                        with l as (
                            insert into links (id, tenant_id, slug, kind, status, utm, created_at, updated_at)
                            values (gen_random_uuid(), '%1$s', 'spring-link', 'STANDARD', 'ACTIVE', '{}', now(), now())
                            returning id
                        )
                        insert into link_destinations (id, tenant_id, link_id, position, url, weight, match, is_fallback)
                        select gen_random_uuid(), '%1$s', l.id, 0, 'https://example.com/x', 1, '{}', true from l
                        """),
                row("link_counters", """
                        with l as (
                            insert into links (id, tenant_id, slug, kind, status, utm, max_clicks, created_at, updated_at)
                            values (gen_random_uuid(), '%1$s', 'spring-link', 'STANDARD', 'ACTIVE', '{}', 100, now(), now())
                            returning id
                        )
                        insert into link_counters (tenant_id, link_id, clicks_counted)
                        select '%1$s', l.id, 0 from l
                        """),
                row("bulk_link_requests", """
                        insert into bulk_link_requests (tenant_id, idempotency_key, request_hash, result, created_at)
                        values ('%1$s', 'key-1', 'hash', '{}', now())
                        """));
    }

    private static Arguments row(String table, String insert) {
        return Arguments.of(table, insert);
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

    private static void insertSettingsAsOwner(UUID tenant) {
        try (Connection owner = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = owner.createStatement()) {
            statement.execute(
                    "insert into attribution_settings (tenant_id, click_param) values ('" + tenant + "', 'go')");
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed attribution_settings", e);
        }
    }

    private static void insertOutboxAsOwner(UUID tenant) {
        try (Connection owner = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = owner.createStatement()) {
            statement.execute("insert into outbox (id, tenant_id, aggregate, type, payload, created_at, next_attempt_at) "
                    + "values ('" + UUID.randomUUID() + "', '" + tenant + "', 'campaign/1', 'campaign.changed', '{}', now(), now())");
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed outbox", e);
        }
    }

    private static void insertSettingsAs(Connection app, UUID bound, UUID target) throws SQLException {
        bind(app, bound);
        try (Statement statement = app.createStatement()) {
            statement.execute(
                    "insert into attribution_settings (tenant_id, click_param) values ('" + target + "', 'x')");
        }
    }
}
