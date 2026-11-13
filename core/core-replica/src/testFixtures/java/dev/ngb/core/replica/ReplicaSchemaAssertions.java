package dev.ngb.core.replica;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Asserts that an app's database satisfies the replica contract: every table a stream upserts into exists, carries
 * every column the writer sets, and has the primary key the upsert's conflict target needs.
 *
 * <p>{@link #applyReferenceDdl(DataSource)} runs the shipped {@code replica-tables.sql}, which is the one DDL source,
 * so a test database has the same shape as the migration an app ships. An app that already owns some of the tables is
 * checked by column and key rather than by DDL.
 */
public final class ReplicaSchemaAssertions {

    /** The shipped reference DDL, on the classpath of every module that depends on the replica. */
    private static final String DDL_RESOURCE = "/dev/ngb/core/replica/replica-tables.sql";

    private final DataSource dataSource;

    public ReplicaSchemaAssertions(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates every replica table from the shipped script, in its order, so a child's foreign key finds its parent. */
    public static void applyReferenceDdl(DataSource dataSource) {
        String ddl;
        try (InputStream stream = ReplicaSchemaAssertions.class.getResourceAsStream(DDL_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("the replica DDL " + DDL_RESOURCE + " is not on the classpath");
            }
            ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read the replica DDL " + DDL_RESOURCE, e);
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            // The simple query protocol runs the whole script, comment lines included, as one statement.
            statement.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("could not apply the reference replica DDL", e);
        }
    }

    /** Fails with every missing column or key, not only the first. */
    public void assertComplete() {
        List<String> problems = new ArrayList<>();
        for (ReplicaStream stream : ReplicaStream.values()) {
            Set<String> columns = columnsOf(stream.table());
            for (String column : stream.columns()) {
                if (!columns.contains(column)) {
                    problems.add(stream.table() + " is missing column " + column);
                }
            }
            if (!hasPrimaryKey(stream.table(), stream.conflictKey())) {
                problems.add(stream.table() + " needs a primary key on " + String.join(", ", stream.conflictKey()));
            }
        }
        assertThat(problems).as("the replica schema must satisfy the contract").isEmpty();
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
    private boolean hasPrimaryKey(String table, List<String> columns) {
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
