package dev.ngb.core.replica;

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
    void theReferenceDdlSatisfiesTheSchemaContract() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        ReplicaSchemaAssertions.applyReferenceDdl(dataSource);

        new ReplicaSchemaAssertions(dataSource).assertComplete();
    }
}
