package dev.ngb.core.replica;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Asserts that an app's database satisfies the replica contract: every table a stream upserts into exists, carries
 * every column the writer sets, and has the unique key the upsert's conflict target needs.
 *
 * <p>Billing already owns four of the tables, so this checks the columns rather than the DDL.
 */
public final class ReplicaSchemaAssertions {

    /** The conflict target each table's upsert relies on. */
    private static final List<TableKey> UNIQUE_KEYS = List.of(
            new TableKey("tenants", List.of("id")),
            new TableKey("tenant_domains", List.of("id")),
            new TableKey("tenant_systems", List.of("id")),
            new TableKey("user_grants", List.of("user_id")),
            new TableKey("app_entitlements", List.of("tenant_id", "app_code")));

    private record TableKey(String table, List<String> columns) {}

    private final DataSource dataSource;

    public ReplicaSchemaAssertions(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Fails with every missing column or key, not only the first. */
    public void assertComplete() {
        List<String> problems = new ArrayList<>();
        for (String stream : ReplicaWriter.streams()) {
            String table = tableOf(stream);
            Set<String> columns = columnsOf(table);
            for (String column : ReplicaWriter.columnsOf(stream)) {
                if (!columns.contains(column)) {
                    problems.add(table + " is missing column " + column);
                }
            }
        }
        for (TableKey key : UNIQUE_KEYS) {
            if (!hasUniqueIndex(key.table(), key.columns())) {
                problems.add(key.table() + " needs a unique key on " + String.join(", ", key.columns()));
            }
        }
        assertThat(problems).as("the replica schema must satisfy the contract").isEmpty();
    }

    /** The table a stream upserts into. */
    public static String tableOf(String stream) {
        return switch (stream) {
            case "tenants" -> "tenants";
            case "tenant-domains" -> "tenant_domains";
            case "tenant-systems" -> "tenant_systems";
            case "user-access" -> "user_grants";
            case "entitlements" -> "app_entitlements";
            default -> throw new IllegalArgumentException("unknown replica stream " + stream);
        };
    }

    private Set<String> columnsOf(String table) {
        Set<String> columns = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
                ResultSet rows = connection.getMetaData().getColumns(null, "public", table, null)) {
            while (rows.next()) {
                columns.add(rows.getString("COLUMN_NAME"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the columns of " + table, e);
        }
        return columns;
    }

    /** True when the table's primary key is exactly the columns given (order-insensitive). */
    private boolean hasUniqueIndex(String table, List<String> columns) {
        try (Connection connection = dataSource.getConnection();
                ResultSet keys = connection.getMetaData().getPrimaryKeys(null, "public", table)) {
            Set<String> found = new HashSet<>();
            while (keys.next()) {
                found.add(keys.getString("COLUMN_NAME"));
            }
            return found.equals(new HashSet<>(columns));
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the primary key of " + table, e);
        }
    }
}
