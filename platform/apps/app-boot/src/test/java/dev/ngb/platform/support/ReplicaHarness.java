package dev.ngb.platform.support;

import dev.ngb.core.replica.ReplicaWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/**
 * A throwaway replica in its own PostgreSQL schema, so a test can prove that the events Platform publishes converge on
 * the same state Billing would store, independent of arrival order. The tables come from the shipped reference DDL in
 * {@code core-replica}, applied with {@code search_path} set to the schema, so unqualified names resolve there.
 */
public final class ReplicaHarness implements AutoCloseable {

    private static final String DDL_RESOURCE = "dev/ngb/core/replica/replica-tables.sql";

    private final Connection connection;
    private final DSLContext dsl;
    private final String schema;

    private ReplicaHarness(Connection connection, DSLContext dsl, String schema) {
        this.connection = connection;
        this.dsl = dsl;
        this.schema = schema;
    }

    /** A fresh, empty replica schema that no other test shares. */
    public static ReplicaHarness create() {
        String schema = "replica_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Connection connection = DriverManager.getConnection(
                    PlatformContainerConfig.postgres().getJdbcUrl(),
                    PlatformContainerConfig.postgres().getUsername(),
                    PlatformContainerConfig.postgres().getPassword());
            connection.setAutoCommit(true);
            // The reference DDL and the writer both use unqualified names, so this session resolves them to its schema.
            try (Statement statement = connection.createStatement()) {
                statement.execute("create schema " + schema);
                statement.execute("set search_path to " + schema + ", public");
                statement.execute(referenceDdl());
            }
            return new ReplicaHarness(connection, DSL.using(connection, SQLDialect.POSTGRES), schema);
        } catch (SQLException e) {
            throw new IllegalStateException("could not prepare the replica schema", e);
        }
    }

    /** Applies one event, exactly as the consuming app's relay does. */
    public boolean apply(String type, Map<String, Object> payload) {
        return new ReplicaWriter(dsl).apply(type, payload);
    }

    /** Upserts a tenant the stream's later rows reference, the way a rebuild goes parent-first. */
    public void tenant(Map<String, Object> payload) {
        new ReplicaWriter(dsl).apply("tenant.changed", payload);
    }

    public List<String> grantedPermissions(UUID userId) {
        String[] permissions = dsl.fetchOne("select permissions from user_grants where user_id = ?", userId)
                .get("permissions", String[].class);
        return new ArrayList<>(List.of(permissions));
    }

    public Map<String, Object> entitlement(UUID tenantId, String appCode) {
        return dsl.fetchOne("select enabled, version from app_entitlements where tenant_id = ? and app_code = ?", tenantId, appCode)
                .intoMap();
    }

    @Override
    public void close() {
        try (Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("could not drop the replica schema", e);
        }
    }

    private static String referenceDdl() throws SQLException {
        try (var stream = ReplicaHarness.class.getClassLoader().getResourceAsStream(DDL_RESOURCE)) {
            if (stream == null) {
                throw new SQLException("the reference replica DDL is missing from the classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new SQLException("could not read the reference replica DDL", e);
        }
    }
}
