package dev.ngb.survey.boot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.survey.support.PostgresContainerConfig;
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
 * A published version is immutable in the database, not by convention: the migrations revoke update and delete from
 * {@code survey_app}, so a stray statement fails with a privilege error. A response recorded against a version keeps
 * rendering after the draft has moved on because nothing can rewrite the document it points at.
 */
class VersionImmutabilityTest {

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
    void theApplicationRoleReadsAndAddsVersionsButNeverRewritesOrDeletesOne() throws SQLException {
        UUID tenant = UUID.randomUUID();
        executeAsOwner("""
                with survey as (
                    insert into surveys (id, tenant_id, name, status, created_at, updated_at)
                    values (gen_random_uuid(), '%1$s', 'Survey', 'DRAFT', now(), now())
                    returning id
                )
                insert into survey_versions (id, survey_id, tenant_id, version_no, document, published_at)
                select gen_random_uuid(), id, '%1$s', 1, '{}'::jsonb, now() from survey
                """
                .formatted(tenant));

        try (Connection app = appRole()) {
            bind(app, tenant);
            assertThat(count(app, "select count(*) from survey_versions")).isEqualTo(1);

            assertThatThrownBy(() -> execute(app, "update survey_versions set version_no = 2"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> execute(app, "delete from survey_versions"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThat(count(app, "select count(*) from survey_versions"))
                    .as("a refused write left the version alone")
                    .isEqualTo(1);
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

    private static Connection appRole() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), PostgresContainerConfig.APP_ROLE, PostgresContainerConfig.APP_PASSWORD);
    }

    private static void bind(Connection connection, UUID tenant) throws SQLException {
        execute(connection, "select set_config('app.tenant_id', '" + tenant + "', false)");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
