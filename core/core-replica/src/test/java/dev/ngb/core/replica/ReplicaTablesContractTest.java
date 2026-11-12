package dev.ngb.core.replica;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The reference DDL is the contract every app's migration must satisfy. This runs the shipped script on a throwaway
 * database and asks {@link ReplicaSchemaAssertions} whether the result is complete.
 */
class ReplicaTablesContractTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    @Test
    void theReferenceDdlSatisfiesTheSchemaContract() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String sql : statements()) {
                statement.execute(sql);
            }
        }

        new ReplicaSchemaAssertions(dataSource).assertComplete();
    }

    /** The script's statements, with comment lines removed. It holds no functions or dollar-quoted bodies. */
    private static List<String> statements() throws IOException {
        String sql;
        try (InputStream stream = ReplicaTablesContractTest.class.getResourceAsStream(
                "/dev/ngb/core/replica/replica-tables.sql")) {
            if (stream == null) {
                throw new IllegalStateException("replica-tables.sql is not on the classpath");
            }
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.strip().startsWith("--")) {
                withoutComments.append(line).append('\n');
            }
        }
        List<String> statements = new ArrayList<>();
        for (String statement : withoutComments.toString().split(";")) {
            if (!statement.isBlank()) {
                statements.add(statement.strip());
            }
        }
        return statements;
    }
}
