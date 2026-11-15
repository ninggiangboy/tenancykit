package dev.ngb.mailer.boot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.mailer.support.PostgresContainerConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
 * The isolation guarantee, proved rather than asserted.
 *
 * <p>Row-level security is only a guarantee if something runs as the unprivileged role with the tenant setting unset: a
 * superuser bypasses every policy, so a suite of tests that connected as one would leave the promise unverified. Every
 * assertion here therefore uses {@code mailer_app}, exactly as a deployment does.
 *
 * <p>The four {@code completed_at} proofs are here for the same reason. A constraint written as
 * {@code (status = 'SUCCEEDED') = (completed_at is not null)} rejects the {@code DEAD} write, which makes the terminal
 * state unreachable and turns every permanent rejection into an infinite resend loop — and it passes every test that
 * only ever writes {@code SUCCEEDED}. So the {@code DEAD} cases are proved first.
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
        insertDeliveryAsOwner(tenant, "r-unbound", "SUCCEEDED");
        try (Connection app = appRole()) {
            assertThat(count(app, "email_deliveries", null)).isZero();
            assertThat(count(app, "mailer_credentials", null)).isZero();
            assertThat(count(app, "event_inbox", null)).isZero();
            assertThat(count(app, "email_bodies", null)).isZero();
        }
    }

    @Test
    void oneTenantCannotSeeOrWriteAnotherTenantsRows() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        insertDeliveryAsOwner(tenantA, "r-owner", "SUCCEEDED");
        insertDeliveryAsOwner(tenantB, "r-other", "SUCCEEDED");

        try (Connection app = appRole()) {
            assertThat(count(app, "email_deliveries", tenantA)).isEqualTo(1);
            assertThat(count(app, "email_deliveries", tenantB)).isEqualTo(1);

            // A write that names another tenant fails the `with check` half of the policy: the row is present in the
            // statement, so only the policy can stop it.
            assertThatThrownBy(() -> insertDeliveryAs(app, tenantB, tenantA, "r-cross"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    @Test
    void deadIsWritableWithACompletionTime() throws SQLException {
        UUID tenant = UUID.randomUUID();
        try (Connection owner = ownerRole()) {
            insertCompletionProbe(owner, tenant, "DEAD", true);
        }
        try (Connection app = appRole()) {
            assertThat(count(app, "email_deliveries", tenant)).isEqualTo(1);
        }
    }

    @Test
    void deadWithoutACompletionTimeIsRefused() throws SQLException {
        UUID tenant = UUID.randomUUID();
        try (Connection owner = ownerRole()) {
            assertThatThrownBy(() -> insertCompletionProbe(owner, tenant, "DEAD", false))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("violates check constraint");
        }
    }

    @Test
    void succeededWithoutACompletionTimeIsRefused() throws SQLException {
        UUID tenant = UUID.randomUUID();
        try (Connection owner = ownerRole()) {
            assertThatThrownBy(() -> insertCompletionProbe(owner, tenant, "SUCCEEDED", false))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("violates check constraint");
        }
    }

    @Test
    void aLiveDeliveryCarryingACompletionTimeIsRefused() throws SQLException {
        UUID tenant = UUID.randomUUID();
        try (Connection owner = ownerRole()) {
            assertThatThrownBy(() -> insertCompletionProbe(owner, tenant, "PENDING", true))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("violates check constraint");
        }
    }

    @Test
    void theInboxCarriesTheColumnTheHousekeepingJobAgesRowsBy() throws SQLException {
        try (Connection owner = ownerRole()) {
            try (Statement statement = owner.createStatement();
                    ResultSet columns = statement.executeQuery("""
                            select column_name from information_schema.columns
                            where table_name = 'event_inbox' order by column_name
                            """)) {
                StringBuilder names = new StringBuilder();
                while (columns.next()) {
                    names.append(columns.getString(1)).append(' ');
                }
                assertThat(names.toString()).contains("processed_at");
            }
            // No foreign key: the inbox records that a delivery was seen, and it must survive one for a tenant this
            // service has not been told about yet.
            try (Statement statement = owner.createStatement();
                    ResultSet constraints = statement.executeQuery("""
                            select count(*) from information_schema.table_constraints
                            where table_name = 'event_inbox' and constraint_type = 'FOREIGN KEY'
                            """)) {
                constraints.next();
                assertThat(constraints.getInt(1)).isZero();
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private static Connection appRole() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), PostgresContainerConfig.APP_ROLE, PostgresContainerConfig.APP_PASSWORD);
    }

    private static Connection ownerRole() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int count(Connection connection, String table, UUID tenantId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            bindTenant(connection, tenantId);
            try (PreparedStatement statement = connection.prepareStatement("select count(*) from " + table);
                    ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            } finally {
                connection.commit();
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            connection.rollback();
            connection.setAutoCommit(true);
            throw failure;
        }
    }

    private static void bindTenant(Connection connection, UUID tenantId) throws SQLException {
        if (tenantId == null) {
            return;
        }
        try (PreparedStatement statement =
                connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenantId.toString());
            statement.execute();
        }
    }

    private static void insertDeliveryAsOwner(UUID tenantId, String requestId, String status) throws SQLException {
        try (Connection owner = ownerRole()) {
            insertCompletionProbe(owner, tenantId, requestId, status, true);
        }
    }

    private static void insertCompletionProbe(Connection owner, UUID tenantId, String status, boolean completed)
            throws SQLException {
        insertCompletionProbe(owner, tenantId, "r-" + UUID.randomUUID(), status, completed);
    }

    private static void insertCompletionProbe(
            Connection owner, UUID tenantId, String requestId, String status, boolean completed) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement("""
                insert into email_deliveries
                    (id, tenant_id, request_id, recipient, from_key, subject, status, attempts, created_at, completed_at)
                values (?, ?, ?, 'someone@example.com', 'default', 'a subject', ?, 1, now(), ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId);
            statement.setString(3, requestId);
            statement.setString(4, status);
            statement.setObject(5, completed ? java.time.OffsetDateTime.now() : null);
            statement.executeUpdate();
        }
    }

    private static void insertDeliveryAs(Connection app, UUID boundTenant, UUID namedTenant, String requestId)
            throws SQLException {
        app.setAutoCommit(false);
        try {
            bindTenant(app, boundTenant);
            try (PreparedStatement statement = app.prepareStatement("""
                    insert into email_deliveries
                        (id, tenant_id, request_id, recipient, from_key, subject, status, attempts, created_at)
                    values (?, ?, ?, 'someone@example.com', 'default', 'a subject', 'PENDING', 0, now())
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, namedTenant);
                statement.setString(3, requestId);
                statement.executeUpdate();
            }
            app.commit();
        } finally {
            app.rollback();
            app.setAutoCommit(true);
        }
    }
}
