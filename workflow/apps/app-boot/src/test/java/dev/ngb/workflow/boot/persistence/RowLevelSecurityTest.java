package dev.ngb.workflow.boot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.workflow.support.PostgresContainerConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The isolation guarantee, proved rather than asserted: the migrations run as the owner and every assertion connects as
 * {@code workflow_app}, the unprivileged role a deployment uses. A superuser would bypass every policy and leave the
 * promise unverified.
 *
 * <p>The skeleton's tenant tables are the inbox, the outbox and the three webhook tables; later phases add one case per
 * table they create.
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
        insertEndpointAsOwner(tenant);

        try (Connection app = appRole()) {
            assertThat(count(app, "webhook_endpoints", null)).isZero();
            assertThat(count(app, "outbox", null)).isZero();
            assertThat(count(app, "event_inbox", null)).isZero();
        }
    }

    @Test
    void oneTenantSeesOnlyItsOwnRowsAndCannotWriteAnothers() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        insertEndpointAsOwner(tenantA);
        insertEndpointAsOwner(tenantB);

        try (Connection app = appRole()) {
            assertThat(count(app, "webhook_endpoints", tenantA)).isEqualTo(1);
            assertThat(count(app, "webhook_endpoints", tenantB)).isEqualTo(1);

            assertThatThrownBy(() -> insertEndpointAs(app, tenantA, tenantB))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    // ------------------------------------------------------------------ every tenant table

    /**
     * One row per tenant table, inserted as the owner. The case is the same for every table: the app role sees nothing
     * until a tenant is bound, and exactly one row once it is. The pattern is the migration's {@code enable_tenant_rls}
     * call, so a table that forgot it fails here.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("tenantTableRows")
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

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> tenantTableRows() {
        return java.util.stream.Stream.of(
                row("workflow_definition", """
                        insert into workflow_definition (id, tenant_id, code, name, status, latest_version, created_at,
                                                         updated_at)
                        values (gen_random_uuid(), '%1$s', 'dunning', 'Dunning', 'DRAFT', 0, now(), now())
                        """),
                row("workflow_version", """
                        with d as (
                            insert into workflow_definition (id, tenant_id, code, name, status, latest_version, created_at,
                                                             updated_at)
                            values (gen_random_uuid(), '%1$s', 'refunds', 'Refunds', 'PUBLISHED', 1, now(), now())
                            returning id
                        )
                        insert into workflow_version (id, tenant_id, definition_id, version, graph, content_hash, published_at)
                        select gen_random_uuid(), '%1$s', d.id, 1, '{}'::jsonb, 'hash', now() from d
                        """),
                row("workflow_instance", """
                        with d as (
                            insert into workflow_definition (id, tenant_id, code, name, status, latest_version, created_at,
                                                             updated_at)
                            values (gen_random_uuid(), '%1$s', 'instances', 'Instances', 'PUBLISHED', 1, now(), now())
                            returning id, tenant_id
                        ), v as (
                            insert into workflow_version (id, tenant_id, definition_id, version, graph, content_hash,
                                                          published_at)
                            select gen_random_uuid(), '%1$s', d.id, 1, '{}'::jsonb, 'hash', now() from d
                            returning id, definition_id
                        )
                        insert into workflow_instance (id, tenant_id, definition_id, version_id, version_number, status,
                                                       input, started_at)
                        select gen_random_uuid(), '%1$s', v.definition_id, v.id, 1, 'RUNNING', '{}'::jsonb, now() from v
                        """),
                row("workflow_token", """
                        with d as (
                            insert into workflow_definition (id, tenant_id, code, name, status, latest_version, created_at,
                                                             updated_at)
                            values (gen_random_uuid(), '%1$s', 'tokens', 'Tokens', 'PUBLISHED', 1, now(), now())
                            returning id, tenant_id
                        ), v as (
                            insert into workflow_version (id, tenant_id, definition_id, version, graph, content_hash,
                                                          published_at)
                            select gen_random_uuid(), '%1$s', d.id, 1, '{}'::jsonb, 'hash', now() from d
                            returning id, definition_id
                        ), i as (
                            insert into workflow_instance (id, tenant_id, definition_id, version_id, version_number, status,
                                                           input, started_at)
                            select gen_random_uuid(), '%1$s', v.definition_id, v.id, 1, 'RUNNING', '{}'::jsonb, now() from v
                            returning id
                        )
                        insert into workflow_token (id, tenant_id, instance_id, node_id, status, available_at, created_at,
                                                    updated_at)
                        select gen_random_uuid(), '%1$s', i.id, 'start', 'READY', now(), now(), now() from i
                        """),
                row("workflow_step_run", """
                        with d as (
                            insert into workflow_definition (id, tenant_id, code, name, status, latest_version, created_at,
                                                             updated_at)
                            values (gen_random_uuid(), '%1$s', 'steps', 'Steps', 'PUBLISHED', 1, now(), now())
                            returning id, tenant_id
                        ), v as (
                            insert into workflow_version (id, tenant_id, definition_id, version, graph, content_hash,
                                                          published_at)
                            select gen_random_uuid(), '%1$s', d.id, 1, '{}'::jsonb, 'hash', now() from d
                            returning id, definition_id
                        ), i as (
                            insert into workflow_instance (id, tenant_id, definition_id, version_id, version_number, status,
                                                           input, started_at)
                            select gen_random_uuid(), '%1$s', v.definition_id, v.id, 1, 'RUNNING', '{}'::jsonb, now() from v
                            returning id
                        )
                        insert into workflow_step_run (id, tenant_id, instance_id, token_id, node_id, attempt, started_at,
                                                       finished_at, outcome, idempotency_key)
                        select gen_random_uuid(), '%1$s', i.id, gen_random_uuid(), 'start', 1, now(), now(), 'SUCCEEDED',
                               repeat('a', 64) from i
                        """),
                row("workflow_trigger", """
                        with d as (
                            insert into workflow_definition (id, tenant_id, code, name, status, latest_version, created_at,
                                                             updated_at)
                            values (gen_random_uuid(), '%1$s', 'hooks', 'Hooks', 'PUBLISHED', 1, now(), now())
                            returning id
                        )
                        insert into workflow_trigger (id, tenant_id, name, kind, source, definition_id, enabled, created_at,
                                                      updated_at)
                        select gen_random_uuid(), '%1$s', 'Manual', 'MANUAL', 'manual', d.id, true, now(), now() from d
                        """),
                row("workflow_inbound_event", """
                        insert into workflow_inbound_event (id, tenant_id, source, event_id, event_type, payload,
                                                            received_at, expires_at)
                        values (gen_random_uuid(), '%1$s', 'billing', gen_random_uuid(),
                                'subscription.payment_failed', '{}'::jsonb, now(), now())
                        """),
                row("workflow_schedule", """
                        with d as (
                            insert into workflow_definition (id, tenant_id, code, name, status, latest_version, created_at,
                                                             updated_at)
                            values (gen_random_uuid(), '%1$s', 'schedules', 'Schedules', 'PUBLISHED', 1, now(), now())
                            returning id
                        ), t as (
                            insert into workflow_trigger (id, tenant_id, name, kind, source, definition_id, enabled,
                                                          created_at, updated_at)
                            select gen_random_uuid(), '%1$s', 'Nightly', 'SCHEDULE', 'schedule', d.id, true, now(), now()
                            from d returning id
                        )
                        insert into workflow_schedule (id, tenant_id, trigger_id, cron, timezone, next_due)
                        select gen_random_uuid(), '%1$s', t.id, '0 2 * * *', 'UTC', now() from t
                        """),
                row("event_inbox", """
                        insert into event_inbox (tenant_id, consumer, event_id)
                        values ('%1$s', 'workflow', gen_random_uuid())
                        """),
                row("outbox", """
                        insert into outbox (id, tenant_id, aggregate, type, payload, created_at, next_attempt_at)
                        values (gen_random_uuid(), '%1$s', 'instance/1', 'instance.completed', '{}', now(), now())
                        """),
                row("webhook_endpoints", """
                        insert into webhook_endpoints (id, tenant_id, url, event_types, status, key_id, secret_ciphertext,
                                                       created_at, updated_at)
                        values (gen_random_uuid(), '%1$s', 'https://example.com/hook', array['instance.completed'],
                                'ACTIVE', 'k1', 'v1.ciphertext', now(), now())
                        """),
                row("webhook_deliveries", """
                        with e as (
                            insert into webhook_endpoints (id, tenant_id, url, event_types, status, key_id, secret_ciphertext,
                                                           created_at, updated_at)
                            values (gen_random_uuid(), '%1$s', 'https://example.com/hook', array['instance.completed'],
                                    'ACTIVE', 'k1', 'v1.ciphertext', now(), now()) returning id
                        )
                        insert into webhook_deliveries (id, tenant_id, endpoint_id, event_id, event_type, body, status,
                                                        next_attempt_at, created_at)
                        select gen_random_uuid(), '%1$s', e.id, gen_random_uuid(), 'instance.completed', '{}', 'PENDING',
                               now(), now() from e
                        """),
                row("webhook_delivery_attempts", """
                        with e as (
                            insert into webhook_endpoints (id, tenant_id, url, event_types, status, key_id, secret_ciphertext,
                                                           created_at, updated_at)
                            values (gen_random_uuid(), '%1$s', 'https://example.com/hook', array['instance.completed'],
                                    'ACTIVE', 'k1', 'v1.ciphertext', now(), now()) returning id
                        ), d as (
                            insert into webhook_deliveries (id, tenant_id, endpoint_id, event_id, event_type, body, status,
                                                            next_attempt_at, created_at)
                            select gen_random_uuid(), '%1$s', e.id, gen_random_uuid(), 'instance.completed', '{}', 'PENDING',
                                   now(), now() from e returning id
                        )
                        insert into webhook_delivery_attempts (id, tenant_id, delivery_id, attempt_no, latency_ms, attempted_at)
                        select gen_random_uuid(), '%1$s', d.id, 1, 12, now() from d
                        """));
    }

    private static org.junit.jupiter.params.provider.Arguments row(String table, String insert) {
        return org.junit.jupiter.params.provider.Arguments.of(table, insert);
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

    private static void insertEndpointAsOwner(UUID tenant) {
        executeAsOwner("insert into webhook_endpoints (id, tenant_id, url, event_types, status, key_id, secret_ciphertext, "
                + "created_at, updated_at) values ('" + UUID.randomUUID() + "', '" + tenant
                + "', 'https://example.com/hook', array['instance.completed'], 'ACTIVE', 'k1', 'v1.ciphertext', now(), now())");
    }

    private static void insertEndpointAs(Connection app, UUID bound, UUID target) throws SQLException {
        bind(app, bound);
        try (Statement statement = app.createStatement()) {
            statement.execute("insert into webhook_endpoints (id, tenant_id, url, event_types, status, key_id, "
                    + "secret_ciphertext, created_at, updated_at) values (gen_random_uuid(), '" + target
                    + "', 'https://example.com/hook', array['instance.completed'], 'ACTIVE', 'k1', 'v1.ciphertext', "
                    + "now(), now())");
        }
    }
}
