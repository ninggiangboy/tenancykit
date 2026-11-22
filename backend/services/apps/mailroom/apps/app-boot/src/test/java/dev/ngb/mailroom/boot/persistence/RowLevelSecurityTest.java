package dev.ngb.mailroom.boot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.mailroom.support.PostgresContainerConfig;
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
 * {@code mailroom_app}, the unprivileged role a deployment uses. A superuser would bypass every policy and leave the
 * promise unverified.
 *
 * <p>{@code email_settings} and {@code outbox} are the two tenant-scoped tables that exist in the skeleton; later
 * phases add one case per table they create.
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
        insertSettingsAsOwner(tenant, "noreply");
        insertOutboxAsOwner(tenant);

        try (Connection app = appRole()) {
            assertThat(count(app, "email_settings", null)).isZero();
            assertThat(count(app, "outbox", null)).isZero();
            assertThat(count(app, "event_inbox", null)).isZero();
        }
    }

    @Test
    void oneTenantSeesOnlyItsOwnRowsAndCannotWriteAnothers() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        insertSettingsAsOwner(tenantA, "a");
        insertSettingsAsOwner(tenantB, "b");

        try (Connection app = appRole()) {
            assertThat(count(app, "email_settings", tenantA)).isEqualTo(1);
            assertThat(count(app, "email_settings", tenantB)).isEqualTo(1);

            assertThatThrownBy(() -> insertSettingsAs(app, tenantA, tenantB, "cross"))
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
                row("email_templates", """
                        insert into email_templates (id, tenant_id, key, name, current_revision, version, created_at, updated_at)
                        values (gen_random_uuid(), '%1$s', 'welcome', 'Welcome', 1, 1, now(), now())
                        """),
                row("email_template_revisions", """
                        insert into email_template_revisions (id, tenant_id, template_id, revision, subject, html, created_at)
                        values (gen_random_uuid(), '%1$s', gen_random_uuid(), 1, 'Hi', '<p>Hi</p>', now())
                        """),
                row("receivers", """
                        insert into receivers (id, tenant_id, email, email_normalized, attributes, status, version, created_at, updated_at)
                        values (gen_random_uuid(), '%1$s', 'A@Example.com', 'a@example.com', '{}', 'SUBSCRIBED', 1, now(), now())
                        """),
                row("receiver_groups", """
                        insert into receiver_groups (id, tenant_id, name, version, created_at, updated_at)
                        values (gen_random_uuid(), '%1$s', 'Customers', 1, now(), now())
                        """),
                row("group_members", """
                        with g as (
                            insert into receiver_groups (id, tenant_id, name, version, created_at, updated_at)
                            values (gen_random_uuid(), '%1$s', 'Customers', 1, now(), now()) returning id
                        ), r as (
                            insert into receivers (id, tenant_id, email, email_normalized, attributes, status, version, created_at, updated_at)
                            values (gen_random_uuid(), '%1$s', 'a@example.com', 'a@example.com', '{}', 'SUBSCRIBED', 1, now(), now()) returning id
                        )
                        insert into group_members (tenant_id, group_id, receiver_id, added_at)
                        select '%1$s', g.id, r.id, now() from g, r
                        """),
                row("suppressions", """
                        insert into suppressions (tenant_id, email_normalized, scope, reason, created_at)
                        values ('%1$s', 'a@example.com', 'MARKETING', 'UNSUBSCRIBED', now())
                        """),
                row("emails", """
                        insert into emails (id, tenant_id, kind, to_address, from_key, subject, status, request_id, created_at, updated_at)
                        values (gen_random_uuid(), '%1$s', 'TRANSACTIONAL', 'a@example.com', 'noreply', 'Hi', 'QUEUED',
                                gen_random_uuid()::text, now(), now())
                        """),
                row("email_events", """
                        with e as (
                            insert into emails (id, tenant_id, kind, to_address, from_key, subject, status, request_id, created_at, updated_at)
                            values (gen_random_uuid(), '%1$s', 'TRANSACTIONAL', 'a@example.com', 'noreply', 'Hi', 'SENT',
                                    gen_random_uuid()::text, now(), now()) returning id
                        )
                        insert into email_events (id, tenant_id, email_id, type, occurred_at)
                        select gen_random_uuid(), '%1$s', e.id, 'SENT', now() from e
                        """),
                row("campaigns", """
                        insert into campaigns (id, tenant_id, name, status, template_id, template_revision, target_type, target_id,
                                               from_key, version, created_at, updated_at)
                        values (gen_random_uuid(), '%1$s', 'Launch', 'DRAFT', gen_random_uuid(), 1, 'GROUP', gen_random_uuid(),
                                'noreply', 1, now(), now())
                        """),
                row("campaign_recipients", """
                        with c as (
                            insert into campaigns (id, tenant_id, name, status, template_id, template_revision, target_type, target_id,
                                                   from_key, version, created_at, updated_at)
                            values (gen_random_uuid(), '%1$s', 'Launch', 'SENDING', gen_random_uuid(), 1, 'GROUP', gen_random_uuid(),
                                    'noreply', 1, now(), now()) returning id
                        )
                        insert into campaign_recipients (tenant_id, campaign_id, receiver_id, state)
                        select '%1$s', c.id, gen_random_uuid(), 'PENDING' from c
                        """),
                row("email_links", """
                        with e as (
                            insert into emails (id, tenant_id, kind, to_address, from_key, subject, status, request_id, created_at, updated_at)
                            values (gen_random_uuid(), '%1$s', 'MARKETING', 'a@example.com', 'noreply', 'Hi', 'SENT',
                                    gen_random_uuid()::text, now(), now()) returning id
                        )
                        insert into email_links (id, tenant_id, email_id, position, url, created_at)
                        select gen_random_uuid(), '%1$s', e.id, 0, 'https://example.com', now() from e
                        """),
                row("segments", """
                        insert into segments (id, tenant_id, name, filter, version, created_at, updated_at)
                        values (gen_random_uuid(), '%1$s', 'Active', '{"field":"status","op":"eq","value":"SUBSCRIBED"}'::jsonb, 1, now(), now())
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

    private static void insertSettingsAsOwner(UUID tenant, String fromKey) {
        try (Connection owner = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = owner.createStatement()) {
            statement.execute("insert into email_settings (tenant_id, from_key) values ('" + tenant + "', '" + fromKey + "')");
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed email_settings", e);
        }
    }

    private static void insertOutboxAsOwner(UUID tenant) {
        try (Connection owner = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = owner.createStatement()) {
            statement.execute("insert into outbox (id, tenant_id, aggregate, type, payload, created_at, next_attempt_at) "
                    + "values ('" + UUID.randomUUID() + "', '" + tenant + "', 'email/1', 'email.send', '{}', now(), now())");
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed outbox", e);
        }
    }

    private static void insertSettingsAs(Connection app, UUID bound, UUID target, String fromKey) throws SQLException {
        bind(app, bound);
        try (Statement statement = app.createStatement()) {
            statement.execute("insert into email_settings (tenant_id, from_key) values ('" + target + "', '" + fromKey + "')");
        }
    }
}
