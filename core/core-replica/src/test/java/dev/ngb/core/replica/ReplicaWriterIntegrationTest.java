package dev.ngb.core.replica;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The guarded upsert: a newer version wins, an older or equal one is a no-op, whatever the arrival order. */
class ReplicaWriterIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    private DSLContext dsl;
    private ReplicaWriter writer;

    @BeforeEach
    void setUp() throws SQLException {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        dsl.execute("drop table if exists user_grants, app_entitlements, tenant_systems, tenant_domains, tenants");
        dsl.execute("""
                create table tenants (
                    id uuid primary key, code text not null unique, name text not null,
                    status text not null, created_at timestamptz not null, version bigint not null default 0)""");
        dsl.execute("""
                create table tenant_domains (
                    id uuid primary key, tenant_id uuid references tenants (id), hostname text not null unique,
                    kind text not null, created_at timestamptz not null, version bigint not null default 0)""");
        dsl.execute("""
                create table tenant_systems (
                    id uuid primary key, tenant_id uuid not null references tenants (id), name text not null,
                    status text not null, created_at timestamptz not null, version bigint not null default 0)""");
        dsl.execute("""
                create table user_grants (
                    user_id uuid primary key, tenant_id uuid not null references tenants (id), scope text not null,
                    keycloak_subject text not null unique, email text not null, display_name text not null,
                    status text not null, permissions text[] not null default '{}', version bigint not null default 0)""");
        dsl.execute("""
                create table app_entitlements (
                    tenant_id uuid not null references tenants (id), app_code text not null, enabled boolean not null,
                    version bigint not null default 0, primary key (tenant_id, app_code))""");
        writer = new ReplicaWriter(dsl);
    }

    @Test
    void aNewerVersionWinsAndAnOlderOneIsIgnored() {
        UUID id = UUID.randomUUID();
        assertThat(writer.apply("tenant.changed", tenant(id, "before", 5))).isTrue();

        assertThat(writer.apply("tenant.changed", tenant(id, "older", 4))).as("an older event is ignored").isFalse();
        assertThat(writer.apply("tenant.changed", tenant(id, "equal", 5))).as("an equal version is ignored").isFalse();
        assertThat(writer.apply("tenant.changed", tenant(id, "newer", 6))).as("a newer event applies").isTrue();

        var row = dsl.fetchOne("select name, version from tenants where id = ?", id);
        assertThat(row.get("name", String.class)).isEqualTo("newer");
        assertThat(row.get("version", Long.class)).isEqualTo(6L);
    }

    @Test
    void arrivalOrderDoesNotMatter() {
        UUID id = UUID.randomUUID();
        writer.apply("tenant.changed", tenant(id, "second", 2));
        writer.apply("tenant.changed", tenant(id, "third", 3));
        writer.apply("tenant.changed", tenant(id, "first", 1));

        var row = dsl.fetchOne("select name, version from tenants where id = ?", id);
        assertThat(row.get("name", String.class)).isEqualTo("third");
        assertThat(row.get("version", Long.class)).isEqualTo(3L);
    }

    @Test
    void theEntitlementGuardIsOnTenantAndApp() {
        UUID tenantId = UUID.randomUUID();
        writer.apply("tenant.changed", tenant(tenantId, "acme", 1));

        assertThat(writer.apply("entitlement.changed", entitlement(tenantId, "billing", true, 1))).isTrue();
        assertThat(writer.apply("entitlement.changed", entitlement(tenantId, "billing", false, 1))).isFalse();
        assertThat(writer.apply("entitlement.changed", entitlement(tenantId, "billing", false, 2))).isTrue();

        Boolean enabled = dsl.fetchOne(
                        "select enabled from app_entitlements where tenant_id = ? and app_code = ?", tenantId, "billing")
                .get("enabled", Boolean.class);
        assertThat(enabled).isFalse();
    }

    @Test
    void tenantsMissingIgnoresThePlatformPseudoTenant() {
        assertThat(writer.tenantsMissing()).as("an empty replica needs a rebuild").isTrue();
        writer.apply("tenant.changed", platformTenant());
        assertThat(writer.tenantsMissing()).as("the platform pseudo-tenant alone is still empty").isTrue();
        writer.apply("tenant.changed", tenant(UUID.randomUUID(), "acme", 1));
        assertThat(writer.tenantsMissing()).as("a real tenant means the replica is loaded").isFalse();
    }

    @Test
    void userGrantsKeepTheirPermissionCodes() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        writer.apply("tenant.changed", tenant(tenantId, "acme", 1));

        Map<String, Object> user = new HashMap<>();
        user.put("user_id", userId.toString());
        user.put("tenant_id", tenantId.toString());
        user.put("scope", "TENANT");
        user.put("keycloak_subject", "kc-" + userId);
        user.put("email", "a@example.com");
        user.put("display_name", "A");
        user.put("status", "ACTIVE");
        user.put("permissions", List.of("billing:invoice:read", "billing:wallet:read"));
        user.put("version", 1);
        assertThat(writer.apply("user-access.changed", user)).isTrue();

        String[] stored = dsl.fetchOne("select permissions from user_grants where user_id = ?", userId)
                .get("permissions", String[].class);
        assertThat(stored).containsExactlyInAnyOrder("billing:invoice:read", "billing:wallet:read");
    }

    /** The reserved platform tenant, whose code is what the rebuild check looks past. */
    private static Map<String, Object> platformTenant() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", RequestScopePlatformTenant.VALUE.toString());
        data.put("code", ReplicaTables.PLATFORM_TENANT_CODE);
        data.put("name", "Platform");
        data.put("status", "ACTIVE");
        data.put("created_at", Instant.now().toString());
        data.put("version", 1);
        return data;
    }

    private static Map<String, Object> tenant(UUID id, String name, long version) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id.toString());
        data.put("code", "c-" + id.toString().substring(0, 8));
        data.put("name", name);
        data.put("status", "ACTIVE");
        data.put("created_at", Instant.now().toString());
        data.put("version", version);
        return data;
    }

    private static Map<String, Object> entitlement(UUID tenantId, String app, boolean enabled, long version) {
        Map<String, Object> data = new HashMap<>();
        data.put("tenant_id", tenantId.toString());
        data.put("app_code", app);
        data.put("enabled", enabled);
        data.put("version", version);
        return data;
    }

    /** The reserved platform tenant id, mirrored here so the writer test does not depend on a service. */
    static final class RequestScopePlatformTenant {
        static final UUID VALUE = UUID.fromString("00000000-0000-7000-8000-000000000001");
    }
}
