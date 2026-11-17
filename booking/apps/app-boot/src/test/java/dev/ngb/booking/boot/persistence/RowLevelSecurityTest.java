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
                        """),
                Arguments.of("resources", """
                        insert into resources (tenant_id, id, code, name, zone, status, created_at, updated_at)
                        values ('%1$s', gen_random_uuid(), 'r-%1$s', 'Resource', 'UTC', 'ACTIVE', now(), now())
                        """),
                Arguments.of("working_hours", """
                        with r as (
                            insert into resources (tenant_id, id, code, name, zone, status, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), 'r-wh-%1$s', 'Resource', 'UTC', 'ACTIVE', now(), now())
                            returning id
                        )
                        insert into working_hours (tenant_id, id, resource_id, weekday, opens_minute, closes_minute)
                        select '%1$s', gen_random_uuid(), r.id, 1, 540, 1020 from r
                        """),
                Arguments.of("calendar_exceptions", """
                        with r as (
                            insert into resources (tenant_id, id, code, name, zone, status, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), 'r-ce-%1$s', 'Resource', 'UTC', 'ACTIVE', now(), now())
                            returning id
                        )
                        insert into calendar_exceptions (tenant_id, id, resource_id, from_date, to_date, kind)
                        select '%1$s', gen_random_uuid(), r.id, current_date, current_date, 'CLOSED' from r
                        """),
                Arguments.of("resource_groups", """
                        insert into resource_groups (tenant_id, id, code, name, strategy, created_at, updated_at)
                        values ('%1$s', gen_random_uuid(), 'g-%1$s', 'Group', 'PRIORITY', now(), now())
                        """),
                Arguments.of("group_members", """
                        with r as (
                            insert into resources (tenant_id, id, code, name, zone, status, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), 'r-gm-%1$s', 'Resource', 'UTC', 'ACTIVE', now(), now())
                            returning id
                        ), g as (
                            insert into resource_groups (tenant_id, id, code, name, strategy, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), 'g-gm-%1$s', 'Group', 'PRIORITY', now(), now())
                            returning id
                        )
                        insert into group_members (tenant_id, group_id, resource_id, priority)
                        select '%1$s', g.id, r.id, 0 from g, r
                        """),
                Arguments.of("offerings", """
                        insert into offerings (tenant_id, id, code, name, kind, status, duration_min, slot_step,
                                               horizon_days, created_at, updated_at)
                        values ('%1$s', gen_random_uuid(), 'o-%1$s', 'Offering', 'INTERVAL', 'ACTIVE', 30, 15, 30,
                                now(), now())
                        """),
                Arguments.of("offering_requirements", """
                        with r as (
                            insert into resources (tenant_id, id, code, name, zone, status, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), 'r-or-%1$s', 'Resource', 'UTC', 'ACTIVE', now(), now())
                            returning id
                        ), o as (
                            insert into offerings (tenant_id, id, code, name, kind, status, duration_min, slot_step,
                                                   horizon_days, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), 'o-or-%1$s', 'Offering', 'INTERVAL', 'ACTIVE', 30, 15,
                                    30, now(), now())
                            returning id
                        )
                        insert into offering_requirements (tenant_id, offering_id, slot_code, resource_id)
                        select '%1$s', o.id, 'staff', r.id from o, r
                        """),
                Arguments.of("cancellation_windows", """
                        with o as (
                            insert into offerings (tenant_id, id, code, name, kind, status, duration_min, slot_step,
                                                   horizon_days, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), 'o-cw-%1$s', 'Offering', 'INTERVAL', 'ACTIVE', 30, 15,
                                    30, now(), now())
                            returning id
                        )
                        insert into cancellation_windows (tenant_id, offering_id, minutes_before_start, code)
                        select '%1$s', o.id, 1440, 'full-refund' from o
                        """),
                Arguments.of("blocks", """
                        with r as (
                            insert into resources (tenant_id, id, code, name, zone, status, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), 'r-bl-%1$s', 'Resource', 'UTC', 'ACTIVE', now(), now())
                            returning id
                        )
                        insert into blocks (tenant_id, id, resource_id, reason, starts_at, ends_at, rule,
                                            duration_minutes, created_at, updated_at)
                        select '%1$s', gen_random_uuid(), r.id, 'Maintenance', now(), now() + interval '2 hours',
                               '{}'::text[], 120, now(), now() from r
                        """),
                Arguments.of("session_series", """
                        with o as (
                            insert into offerings (tenant_id, id, code, name, kind, status, duration_min, slot_step,
                                                   horizon_days, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), 'o-ss-%1$s', 'Offering', 'CAPACITY', 'ACTIVE', 60, 60,
                                    90, now(), now())
                            returning id
                        )
                        insert into session_series (tenant_id, id, offering_id, zone, dtstart, rule, duration_minutes,
                                                    capacity, status, created_at, updated_at)
                        select '%1$s', gen_random_uuid(), o.id, 'Europe/Berlin', '2026-03-23T18:00:00',
                               array['RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=3'], 60, 10, 'ACTIVE', now(), now() from o
                        """),
                Arguments.of("sessions", """
                        with o as (
                            insert into offerings (tenant_id, id, code, name, kind, status, duration_min, slot_step,
                                                   horizon_days, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), 'o-se-%1$s', 'Offering', 'CAPACITY', 'ACTIVE', 60, 60,
                                    90, now(), now())
                            returning id
                        )
                        insert into sessions (tenant_id, id, offering_id, starts_at, ends_at, status, capacity,
                                              created_at, updated_at)
                        select '%1$s', gen_random_uuid(), o.id, now() + interval '1 day',
                               now() + interval '1 day 1 hour', 'SCHEDULED', 10, now(), now() from o
                        """),
                Arguments.of("session_pools", """
                        with o as (
                            insert into offerings (tenant_id, id, code, name, kind, status, duration_min, slot_step,
                                                   horizon_days, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), 'o-sp-%1$s', 'Offering', 'CAPACITY', 'ACTIVE', 60, 60,
                                    90, now(), now())
                            returning id
                        ), s as (
                            insert into sessions (tenant_id, id, offering_id, starts_at, ends_at, status, capacity,
                                                  created_at, updated_at)
                            select '%1$s', gen_random_uuid(), o.id, now() + interval '1 day',
                                   now() + interval '1 day 1 hour', 'SCHEDULED', 10, now(), now() from o
                            returning id
                        )
                        insert into session_pools (tenant_id, session_id, code, capacity)
                        select '%1$s', s.id, 'default', 10 from s
                        """),
                Arguments.of("interval_claims", """
                        with r as (
                            insert into resources (tenant_id, id, code, name, zone, status, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), 'r-ic-%1$s', 'Resource', 'UTC', 'ACTIVE', now(), now())
                            returning id
                        )
                        insert into interval_claims (tenant_id, id, resource_id, booking_id, during, expires_at, created_at)
                        select '%1$s', gen_random_uuid(), r.id, gen_random_uuid(),
                               tstzrange(now(), now() + interval '1 hour', '[)'), null, now()
                        from r
                        """),
                Arguments.of("capacity_units", """
                        with o as (
                            insert into offerings (tenant_id, id, code, name, kind, status, duration_min, slot_step,
                                                   horizon_days, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), 'o-cu-%1$s', 'Offering', 'CAPACITY', 'ACTIVE', 30, 15,
                                    30, now(), now())
                            returning id
                        ), s as (
                            insert into sessions (tenant_id, id, series_id, offering_id, starts_at, ends_at, status,
                                                  capacity, created_at, updated_at)
                            select '%1$s', gen_random_uuid(), null, o.id, now(), now() + interval '1 hour',
                                   'SCHEDULED', 10, now(), now()
                            from o
                            returning id
                        )
                        insert into capacity_units (tenant_id, id, session_id, pool_code, status)
                        select '%1$s', gen_random_uuid(), s.id, 'default', 'AVAILABLE' from s
                        """),
                Arguments.of("bookings", """
                        insert into bookings (tenant_id, id, offering_id, offering_kind, customer_ref, status, qty,
                                              source, created_at, updated_at)
                        values ('%1$s', gen_random_uuid(), gen_random_uuid(), 'INTERVAL', 'cust-1', 'HELD', 1,
                                'TENANT', now(), now())
                        """),
                Arguments.of("booking_resources", """
                        with b as (
                            insert into bookings (tenant_id, id, offering_id, offering_kind, customer_ref, status, qty,
                                                  source, created_at, updated_at)
                            values ('%1$s', gen_random_uuid(), gen_random_uuid(), 'INTERVAL', 'cust-1', 'HELD', 1,
                                    'TENANT', now(), now())
                            returning id
                        )
                        insert into booking_resources (tenant_id, booking_id, slot_code, resource_id)
                        select '%1$s', b.id, 'staff', gen_random_uuid() from b
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
