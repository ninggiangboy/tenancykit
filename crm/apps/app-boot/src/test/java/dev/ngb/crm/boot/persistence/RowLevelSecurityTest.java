package dev.ngb.crm.boot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.crm.support.PostgresContainerConfig;
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
import org.junit.jupiter.params.provider.Arguments;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The isolation guarantee, proved rather than asserted: the migrations run as the owner and every assertion connects as
 * {@code crm_app}, the unprivileged role a deployment uses. A superuser would bypass every policy and leave the promise
 * unverified.
 *
 * <p>Ten tenant-scoped tables exist — the inbox, the outbox, the two webhook tables, the attempt log, the customers,
 * their tags and consent records, the field definitions and the consent topics — and the parameterized case covers one
 * row of each. A new table adds its row here.
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
        executeAsOwner(outboxRow().formatted(tenant));
        executeAsOwner(endpointRow().formatted(tenant));

        try (Connection app = appRole()) {
            assertThat(count(app, "outbox", null)).isZero();
            assertThat(count(app, "event_inbox", null)).isZero();
            assertThat(count(app, "webhook_endpoints", null)).isZero();
            assertThat(count(app, "webhook_deliveries", null)).isZero();
            assertThat(count(app, "webhook_delivery_attempts", null)).isZero();
            assertThat(count(app, "customers", null)).isZero();
            assertThat(count(app, "field_definitions", null)).isZero();
            assertThat(count(app, "customer_tags", null)).isZero();
            assertThat(count(app, "consent_topics", null)).isZero();
            assertThat(count(app, "consent_events", null)).isZero();
        }
    }

    @Test
    void oneTenantSeesOnlyItsOwnRowsAndCannotWriteAnothers() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        executeAsOwner(endpointRow().formatted(tenantA));
        executeAsOwner(endpointRow().formatted(tenantB));

        try (Connection app = appRole()) {
            assertThat(count(app, "webhook_endpoints", tenantA)).isEqualTo(1);
            assertThat(count(app, "webhook_endpoints", tenantB)).isEqualTo(1);

            assertThatThrownBy(() -> insertEndpointAs(app, tenantA, tenantB))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    /** The attempt log is append-only for the app role: it may insert and it may not rewrite what came back. */
    @Test
    void theAppRoleCannotRewriteAnAttempt() throws SQLException {
        UUID tenant = UUID.randomUUID();
        executeAsOwner(attemptRow().formatted(tenant));

        try (Connection app = appRole()) {
            bind(app, tenant);
            try (Statement statement = app.createStatement()) {
                assertThatThrownBy(() -> statement.execute("update webhook_delivery_attempts set latency_ms = 1"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
        }
    }

    /** A consent record is evidence: the app role may append one and may not rewrite the history behind it. */
    @Test
    void theAppRoleCannotRewriteAConsentRecord() throws SQLException {
        UUID tenant = UUID.randomUUID();
        executeAsOwner(consentRow().formatted(tenant));

        try (Connection app = appRole()) {
            bind(app, tenant);
            try (Statement statement = app.createStatement()) {
                assertThatThrownBy(() -> statement.execute("update consent_events set status = 'REVOKED'"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
        }
    }

    /** One row per tenant table. A table that forgot its {@code enable_tenant_rls} call fails here. */
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

    static Stream<Arguments> tenantTableRows() {
        return Stream.of(
                Arguments.of("event_inbox", """
                        insert into event_inbox (tenant_id, consumer, event_id) values ('%1$s', 'crm-platform-events', gen_random_uuid())
                        """),
                Arguments.of("outbox", outboxRow()),
                Arguments.of("customers", customerRow()),
                Arguments.of("field_definitions", fieldDefinitionRow()),
                Arguments.of("customer_tags", customerTagRow()),
                Arguments.of("consent_topics", consentTopicRow()),
                Arguments.of("consent_events", consentRow()),
                Arguments.of("orders", orderRow()),
                Arguments.of("order_lines", orderLineRow()),
                Arguments.of("order_returns", orderReturnRow()),
                Arguments.of("order_return_lines", orderReturnLineRow()),
                Arguments.of("webhook_endpoints", endpointRow()),
                Arguments.of("webhook_deliveries", deliveryRow()),
                Arguments.of("webhook_delivery_attempts", attemptRow()));
    }

    private static String outboxRow() {
        return """
                insert into outbox (id, tenant_id, aggregate, type, payload, created_at, next_attempt_at)
                values (gen_random_uuid(), '%1$s', 'customer/1', 'customer.created', '{}'::jsonb, now(), now())
                """;
    }

    private static String customerRow() {
        return """
                insert into customers (tenant_id, id, external_ref, display_name, phone, phone_digits, email, status,
                                       version, created_at, updated_at)
                values ('%1$s', gen_random_uuid(), 'cust-' || gen_random_uuid(), 'Ada Lovelace', '+44 20 7946 0958',
                        '442079460958', 'ada@example.com', 'ACTIVE', 1, now(), now())
                """;
    }

    private static String fieldDefinitionRow() {
        return """
                insert into field_definitions (tenant_id, key, label, type, choices, archived, created_at)
                values ('%1$s', 'size', 'Size', 'CHOICE', array['S', 'M'], false, now())
                """;
    }

    private static String customerTagRow() {
        return """
                with customer as (
                    insert into customers (tenant_id, id, external_ref, status, version, created_at, updated_at)
                    values ('%1$s', gen_random_uuid(), 'cust-' || gen_random_uuid(), 'ACTIVE', 1, now(), now())
                    returning id
                )
                insert into customer_tags (tenant_id, customer_id, tag)
                select '%1$s', customer.id, 'vip' from customer
                """;
    }

    private static String consentTopicRow() {
        return """
                insert into consent_topics (tenant_id, key, label, archived)
                values ('%1$s', 'profiling', 'Profiling', false)
                """;
    }

    private static String consentRow() {
        return """
                with customer as (
                    insert into customers (tenant_id, id, external_ref, status, version, created_at, updated_at)
                    values ('%1$s', gen_random_uuid(), 'cust-' || gen_random_uuid(), 'ACTIVE', 1, now(), now())
                    returning id
                )
                insert into consent_events (id, tenant_id, customer_id, topic_key, status, occurred_at, recorded_at,
                                            source, consent_ref)
                select gen_random_uuid(), '%1$s', customer.id, 'profiling', 'GRANTED', now(), now(), 'pos',
                       'ref-' || gen_random_uuid()
                from customer
                """;
    }

    private static String orderRow() {
        return """
                with customer as (
                    insert into customers (tenant_id, id, external_ref, status, version, created_at, updated_at)
                    values ('%1$s', gen_random_uuid(), 'cust-' || gen_random_uuid(), 'ACTIVE', 1, now(), now())
                    returning id
                )
                insert into orders (tenant_id, id, order_ref, customer_id, currency, total, occurred_at, store_code,
                                    channel, status, version, fingerprint, created_at)
                select '%1$s', gen_random_uuid(), 'order-' || gen_random_uuid(), customer.id, 'EUR', 1299, now(), 'S1',
                       'pos', 'RECORDED', 1, 'fingerprint', now()
                from customer
                """;
    }

    private static String orderLineRow() {
        return """
                with customer as (
                    insert into customers (tenant_id, id, external_ref, status, version, created_at, updated_at)
                    values ('%1$s', gen_random_uuid(), 'cust-' || gen_random_uuid(), 'ACTIVE', 1, now(), now())
                    returning id
                ), placed as (
                    insert into orders (tenant_id, id, order_ref, customer_id, currency, total, occurred_at, store_code,
                                        channel, status, version, fingerprint, created_at)
                    select '%1$s', gen_random_uuid(), 'order-' || gen_random_uuid(), customer.id, 'EUR', 1299, now(),
                           'S1', 'pos', 'RECORDED', 1, 'fingerprint', now()
                    from customer
                    returning id
                )
                insert into order_lines (tenant_id, order_id, line_no, sku, name, category, quantity, amount,
                                          earn_eligible, returned_quantity, returned_amount)
                select '%1$s', placed.id, 1, 'SKU-1', 'Coffee', 'grocery', 2.000, 1299, true, 0, 0 from placed
                """;
    }

    private static String orderReturnRow() {
        return """
                with customer as (
                    insert into customers (tenant_id, id, external_ref, status, version, created_at, updated_at)
                    values ('%1$s', gen_random_uuid(), 'cust-' || gen_random_uuid(), 'ACTIVE', 1, now(), now())
                    returning id
                ), placed as (
                    insert into orders (tenant_id, id, order_ref, customer_id, currency, total, occurred_at, store_code,
                                        channel, status, version, fingerprint, created_at)
                    select '%1$s', gen_random_uuid(), 'order-' || gen_random_uuid(), customer.id, 'EUR', 1299, now(),
                           'S1', 'pos', 'RECORDED', 1, 'fingerprint', now()
                    from customer
                    returning id
                )
                insert into order_returns (tenant_id, id, order_id, return_ref, occurred_at, version, fingerprint,
                                            created_at)
                select '%1$s', gen_random_uuid(), placed.id, 'return-' || gen_random_uuid(), now(), 2, 'fingerprint',
                       now()
                from placed
                """;
    }

    private static String orderReturnLineRow() {
        return """
                with customer as (
                    insert into customers (tenant_id, id, external_ref, status, version, created_at, updated_at)
                    values ('%1$s', gen_random_uuid(), 'cust-' || gen_random_uuid(), 'ACTIVE', 1, now(), now())
                    returning id
                ), placed as (
                    insert into orders (tenant_id, id, order_ref, customer_id, currency, total, occurred_at, store_code,
                                        channel, status, version, fingerprint, created_at)
                    select '%1$s', gen_random_uuid(), 'order-' || gen_random_uuid(), customer.id, 'EUR', 1299, now(),
                           'S1', 'pos', 'RECORDED', 1, 'fingerprint', now()
                    from customer
                    returning id
                ), returned as (
                    insert into order_returns (tenant_id, id, order_id, return_ref, occurred_at, version, fingerprint,
                                                created_at)
                    select '%1$s', gen_random_uuid(), placed.id, 'return-' || gen_random_uuid(), now(), 2,
                           'fingerprint', now()
                    from placed
                    returning id
                )
                insert into order_return_lines (tenant_id, return_id, line_no, quantity, amount)
                select '%1$s', returned.id, 1, 1.000, 650 from returned
                """;
    }

    private static String endpointRow() {
        return """
                insert into webhook_endpoints (id, tenant_id, url, event_types, status, key_id, secret_ciphertext,
                                               created_at, updated_at)
                values (gen_random_uuid(), '%1$s', 'https://hooks.example.com/crm', array['customer.created'], 'ACTIVE',
                        'whk_test', 'v1.k1.ciphertext', now(), now())
                """;
    }

    private static String deliveryRow() {
        return """
                with endpoint as (
                    insert into webhook_endpoints (id, tenant_id, url, event_types, status, key_id, secret_ciphertext,
                                                   created_at, updated_at)
                    values (gen_random_uuid(), '%1$s', 'https://hooks.example.com/crm', array['customer.created'],
                            'ACTIVE', 'whk_test', 'v1.k1.ciphertext', now(), now())
                    returning id
                )
                insert into webhook_deliveries (id, tenant_id, endpoint_id, event_id, event_type, body, status,
                                                next_attempt_at, created_at)
                select gen_random_uuid(), '%1$s', endpoint.id, gen_random_uuid(), 'customer.created', '{}', 'PENDING',
                       now(), now()
                from endpoint
                """;
    }

    private static String attemptRow() {
        return """
                with endpoint as (
                    insert into webhook_endpoints (id, tenant_id, url, event_types, status, key_id, secret_ciphertext,
                                                   created_at, updated_at)
                    values (gen_random_uuid(), '%1$s', 'https://hooks.example.com/crm', array['customer.created'],
                            'ACTIVE', 'whk_test', 'v1.k1.ciphertext', now(), now())
                    returning id
                ), delivery as (
                    insert into webhook_deliveries (id, tenant_id, endpoint_id, event_id, event_type, body, status,
                                                    next_attempt_at, created_at)
                    select gen_random_uuid(), '%1$s', endpoint.id, gen_random_uuid(), 'customer.created', '{}',
                           'PENDING', now(), now()
                    from endpoint
                    returning id
                )
                insert into webhook_delivery_attempts (id, tenant_id, delivery_id, attempt_no, status_code, latency_ms,
                                                       attempted_at)
                select gen_random_uuid(), '%1$s', delivery.id, 1, 500, 5, now() from delivery
                """;
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

    private static void insertEndpointAs(Connection app, UUID bound, UUID target) throws SQLException {
        bind(app, bound);
        try (Statement statement = app.createStatement()) {
            statement.execute("""
                    insert into webhook_endpoints (id, tenant_id, url, event_types, status, key_id, secret_ciphertext,
                                                   created_at, updated_at)
                    values (gen_random_uuid(), '%s', 'https://hooks.example.com/cross', array['customer.created'],
                            'ACTIVE', 'whk_test', 'v1.k1.ciphertext', now(), now())
                    """.formatted(target));
        }
    }
}
