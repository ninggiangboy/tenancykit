package dev.ngb.attribution.boot.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.support.PostgresContainerConfig;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The privacy gate: no table may hold a raw address or a user-agent string.
 *
 * <p>The whole design keeps only classified facts, so a column that stores an address, an {@code inet}, a network type
 * or an agent string is a mistake the build must catch rather than a rule a reviewer must remember. This reads
 * {@code information_schema} from the real migrations, so a later migration that adds such a column fails here.
 */
class PrivacySchemaTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);

    static {
        POSTGRES.start();
    }

    /** Column names that mean an address was stored, in the forms a careless migration would choose. */
    private static final List<String> ADDRESS_COLUMN_NAMES =
            List.of("ip", "ip_address", "client_ip", "remote_addr", "remote_address", "addr", "address");

    /** Column name fragments that mean a raw user agent was stored. */
    private static final List<String> AGENT_COLUMN_FRAGMENTS = List.of("user_agent", "useragent", "user-agent");

    /** Column types that hold an address natively. */
    private static final List<String> ADDRESS_TYPES = List.of("inet", "cidr", "macaddr", "macaddr8");

    @Test
    void noTableStoresARawAddressOrAUserAgent() {
        MigrateResult migrated = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("appDbPassword", PostgresContainerConfig.APP_PASSWORD))
                .load()
                .migrate();
        assertThat(migrated.migrationsExecuted).isGreaterThan(0);

        PGSimpleDataSource owner = new PGSimpleDataSource();
        owner.setUrl(POSTGRES.getJdbcUrl());
        owner.setUser(POSTGRES.getUsername());
        owner.setPassword(POSTGRES.getPassword());
        DSLContext dsl = DSL.using(owner, SQLDialect.POSTGRES);

        List<Record> columns = dsl.fetch(
                "select table_name, column_name, data_type from information_schema.columns"
                        + " where table_schema = 'public'");

        List<String> offenders = columns.stream()
                .filter(column -> {
                    String name = column.get("column_name", String.class).toLowerCase(Locale.ROOT);
                    String type = column.get("data_type", String.class).toLowerCase(Locale.ROOT);
                    return ADDRESS_COLUMN_NAMES.contains(name)
                            || AGENT_COLUMN_FRAGMENTS.stream().anyMatch(name::contains)
                            || ADDRESS_TYPES.contains(type);
                })
                .map(column -> column.get("table_name", String.class) + "." + column.get("column_name", String.class)
                        + " (" + column.get("data_type", String.class) + ")")
                .toList();

        assertThat(offenders)
                .as("no table may store a raw address or a user agent; the click keeps a classified device class and a"
                        + " daily visitor hash instead")
                .isEmpty();
    }
}
