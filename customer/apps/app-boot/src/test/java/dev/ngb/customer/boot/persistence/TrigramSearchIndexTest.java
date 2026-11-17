package dev.ngb.customer.boot.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.customer.support.PostgresContainerConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The search the customer list grows needs the trigram extension, and installing an extension is the one migration step
 * a table owner can lack the right for. This test answers the question rather than assuming it: a role shaped like a
 * deployment's owner — not a superuser, no createdb, no createrole — owns a fresh database and runs the whole migration
 * set in it.
 *
 * <p>It passes when the migrations finish and {@code pg_trgm} is installed, which proves contains-search over a name or
 * an address is available. A migration that named {@code pg_trgm} without this right would fail here first, and the
 * fallback would be a prefix search over {@code lower(display_name)} with {@code text_pattern_ops}.
 */
class TrigramSearchIndexTest {

    private static final PostgreSQLContainer POSTGRES = PostgresContainerConfig.postgres();

    @Test
    void theMigrationOwnerInstallsTheTrigramExtensionWithoutBeingASuperuser() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String owner = "probe_owner_" + suffix;
        String database = "probe_" + suffix;
        try {
            create(owner, database);
            Flyway.configure()
                    .dataSource(urlOf(database), owner, PostgresContainerConfig.APP_PASSWORD)
                    .locations("classpath:db/migration")
                    .placeholders(Map.of("appDbPassword", PostgresContainerConfig.APP_PASSWORD))
                    .load()
                    .migrate();

            assertThat(query("select extname from pg_extension", database, owner))
                    .as("the migration installed the trigram extension")
                    .contains("pg_trgm");
            assertThat(query("select indexname from pg_indexes where tablename = 'customers'", database, owner))
                    .as("the customer table and the indexes this endpoint reads through exist")
                    .contains(
                            "customers_pkey",
                            "customers_tenant_id_external_ref_key",
                            "customers_phone_digits_idx",
                            "customers_email_idx",
                            "customers_keyset_idx");
        } finally {
            drop(owner, database);
        }
    }

    private static void create(String owner, String database) throws SQLException {
        try (Connection admin = admin(); Statement statement = admin.createStatement()) {
            // The deployment's init script creates the application role; the migrations create it only when the owner
            // may, so the probe supplies it first and the owner stays as unprivileged as a deployment's.
            statement.execute(
                    "do $$ begin if not exists (select from pg_roles where rolname = '"
                            + PostgresContainerConfig.APP_ROLE
                            + "') then create role "
                            + PostgresContainerConfig.APP_ROLE
                            + " login password '"
                            + PostgresContainerConfig.APP_PASSWORD
                            + "' nosuperuser nocreatedb nocreaterole nobypassrls; end if; end $$");
            // The shape of a deployment's owner exactly: it may log in and owns its database, nothing more.
            statement.execute(
                    "create role " + owner + " login password '" + PostgresContainerConfig.APP_PASSWORD
                            + "' nocreatedb nocreaterole");
            statement.execute("create database " + database + " owner " + owner);
        }
    }

    private static void drop(String owner, String database) {
        try (Connection admin = admin(); Statement statement = admin.createStatement()) {
            statement.execute("drop database if exists " + database + " with (force)");
            statement.execute("drop role if exists " + owner);
        } catch (SQLException e) {
            throw new IllegalStateException("could not remove the probe role and database", e);
        }
    }

    private static List<String> query(String sql, String database, String user) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        urlOf(database), user, PostgresContainerConfig.APP_PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            List<String> column = new ArrayList<>();
            while (rows.next()) {
                column.add(rows.getString(1));
            }
            return column;
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String urlOf(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }
}
