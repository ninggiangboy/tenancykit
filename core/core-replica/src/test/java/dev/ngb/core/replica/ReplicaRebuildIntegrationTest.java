package dev.ngb.core.replica;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The rebuild-from-zero path: an empty database, a snapshot server holding a few rows of each stream, then a queued
 * event with an older version (ignored) and a newer one (applied).
 */
class ReplicaRebuildIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    private DSLContext dsl;
    private UnitOfWork unitOfWork;
    private ScopedTenantContext tenantContext;
    private ReplicaWriter writer;
    private final RecordingTransport transport = new RecordingTransport();

    @BeforeEach
    void setUp() throws SQLException {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("drop table if exists user_grants, app_entitlements, tenant_systems, tenant_domains, tenants");
            statement.execute("""
                    create table tenants (
                        id uuid primary key, code text not null unique, name text not null,
                        status text not null, created_at timestamptz not null, version bigint not null default 0)""");
            statement.execute("""
                    create table tenant_domains (
                        id uuid primary key, tenant_id uuid references tenants (id), hostname text not null unique,
                        kind text not null, created_at timestamptz not null, version bigint not null default 0)""");
            statement.execute("""
                    create table tenant_systems (
                        id uuid primary key, tenant_id uuid not null references tenants (id), name text not null,
                        status text not null, created_at timestamptz not null, version bigint not null default 0)""");
            statement.execute("""
                    create table user_grants (
                        user_id uuid primary key, tenant_id uuid not null references tenants (id), scope text not null,
                        keycloak_subject text not null unique, email text not null, display_name text not null,
                        status text not null, permissions text[] not null default '{}', version bigint not null default 0)""");
            statement.execute("""
                    create table app_entitlements (
                        tenant_id uuid not null references tenants (id), app_code text not null, enabled boolean not null,
                        version bigint not null default 0, primary key (tenant_id, app_code))""");
        }
        dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
        unitOfWork = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource), TransactionScopeHook.none());
        tenantContext = new ScopedTenantContext();
        writer = new ReplicaWriter(dsl);
    }

    @Test
    void anEmptyReplicaIsRebuiltFromTheSnapshotThenLiveEventsApply() {
        Snapshot snapshot = seedSnapshot();
        ReplicaBootstrap bootstrap = bootstrap();

        assertThat(bootstrap.needsRebuild()).isTrue();
        bootstrap.rebuild();

        assertThat(rowCount("tenants")).isEqualTo(3);
        assertThat(rowCount("tenant_domains")).isEqualTo(2);
        assertThat(rowCount("tenant_systems")).isEqualTo(1);
        assertThat(rowCount("user_grants")).isEqualTo(2);
        assertThat(rowCount("app_entitlements")).isEqualTo(2);

        // A queued event the snapshot already covered is ignored, and a newer one applies.
        assertThat(writer.apply("tenant.changed", snapshot.tenantWithVersion(1))).as("the snapshot covered it").isFalse();
        assertThat(writer.apply("tenant.changed", snapshot.tenantWithVersion(9))).isTrue();
        assertThat(dsl.fetchOne("select version from tenants where id = ?", snapshot.firstTenantId())
                        .get("version", Long.class))
                .isEqualTo(9L);
    }

    @Test
    void aLoadedReplicaIsNotRebuilt() {
        seedSnapshot();
        ReplicaBootstrap bootstrap = bootstrap();
        bootstrap.rebuild();
        transport.reset();

        assertThat(bootstrap.needsRebuild()).isFalse();
        assertThat(transport.calls).as("nothing is paged when the replica is loaded").isEmpty();
    }

    @Test
    void theListenerStartsAfterTheRebuild() {
        seedSnapshot();
        AtomicBoolean started = new AtomicBoolean();
        ReplicaBootstrap bootstrap = new ReplicaBootstrap(
                properties(), writer, new SnapshotClient(transport), unitOfWork, tenantContext, () -> {
                    started.set(true);
                    assertThat(transport.calls).as("the snapshot is paged before the listener starts").isNotEmpty();
                });

        bootstrap.run(null);

        assertThat(started).isTrue();
    }

    private ReplicaBootstrap bootstrap() {
        return new ReplicaBootstrap(
                properties(), writer, new SnapshotClient(transport), unitOfWork, tenantContext, () -> {});
    }

    private ReplicaProperties properties() {
        return new ReplicaProperties("billing", new ReplicaProperties.Replica(
                "http://platform.test", "billing", "secret", true, 100, java.time.Duration.ofSeconds(5)));
    }

    private long rowCount(String table) {
        return dsl.fetchCount(DSL.table(DSL.name(table)));
    }

    private Snapshot seedSnapshot() {
        Snapshot snapshot = new Snapshot();
        transport.on("tenants", snapshot::tenantPages);
        transport.on("tenant-domains", () -> List.of(snapshot.domainPage()));
        transport.on("tenant-systems", () -> List.of(snapshot.systemPage()));
        transport.on("user-access", () -> List.of(snapshot.userPage()));
        transport.on("entitlements", () -> List.of(snapshot.entitlementPage()));
        return snapshot;
    }

    /** A tiny in-memory snapshot server: a few rows per stream, paged with a cursor. */
    private static final class Snapshot {
        private final UUID platformId = UUID.fromString("00000000-0000-7000-8000-000000000001");
        private final List<UUID> tenantIds = new ArrayList<>();

        Snapshot() {
            tenantIds.add(platformId);
            tenantIds.add(UUID.randomUUID());
            tenantIds.add(UUID.randomUUID());
        }

        UUID firstTenantId() {
            return tenantIds.get(1);
        }

        List<SnapshotClient.Page> tenantPages() {
            List<Map<String, Object>> items = tenantIds.stream().map(this::tenant).toList();
            return page(items, 2);
        }

        Map<String, Object> tenantWithVersion(long version) {
            Map<String, Object> item = tenant(tenantIds.get(1));
            item.put("version", version);
            return item;
        }

        SnapshotClient.Page domainPage() {
            List<Map<String, Object>> items = new ArrayList<>();
            items.add(domain(UUID.randomUUID(), null, "platform.localhost", "PLATFORM"));
            items.add(domain(UUID.randomUUID(), tenantIds.get(1), "acme.localhost", "TENANT_SUBDOMAIN"));
            return new SnapshotClient.Page(items, null);
        }

        SnapshotClient.Page systemPage() {
            Map<String, Object> item = new HashMap<>();
            item.put("id", UUID.randomUUID().toString());
            item.put("tenant_id", tenantIds.get(1).toString());
            item.put("name", "Partner");
            item.put("status", "ACTIVE");
            item.put("created_at", Instant.now().toString());
            item.put("version", 1);
            return new SnapshotClient.Page(List.of(item), null);
        }

        SnapshotClient.Page userPage() {
            List<Map<String, Object>> items = new ArrayList<>();
            items.add(user(UUID.randomUUID(), tenantIds.get(1)));
            items.add(user(UUID.randomUUID(), tenantIds.get(2)));
            return new SnapshotClient.Page(items, null);
        }

        SnapshotClient.Page entitlementPage() {
            List<Map<String, Object>> items = new ArrayList<>();
            items.add(entitlement(tenantIds.get(1), "billing", true));
            items.add(entitlement(tenantIds.get(2), "billing", false));
            return new SnapshotClient.Page(items, null);
        }

        private Map<String, Object> tenant(UUID id) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", id.toString());
            item.put("code", id.equals(platformId) ? "platform" : "c-" + id.toString().substring(0, 8));
            item.put("name", "Tenant " + id);
            item.put("status", "ACTIVE");
            item.put("created_at", Instant.now().toString());
            item.put("version", 1);
            return item;
        }

        private static Map<String, Object> domain(UUID id, UUID tenantId, String hostname, String kind) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", id.toString());
            item.put("tenant_id", tenantId == null ? null : tenantId.toString());
            item.put("hostname", hostname);
            item.put("kind", kind);
            item.put("created_at", Instant.now().toString());
            item.put("version", 1);
            return item;
        }

        private static Map<String, Object> user(UUID id, UUID tenantId) {
            Map<String, Object> item = new HashMap<>();
            item.put("user_id", id.toString());
            item.put("tenant_id", tenantId.toString());
            item.put("scope", "TENANT");
            item.put("keycloak_subject", "kc-" + id);
            item.put("email", id + "@example.com");
            item.put("display_name", "User " + id);
            item.put("status", "ACTIVE");
            item.put("permissions", List.of("billing:invoice:read"));
            item.put("version", 1);
            return item;
        }

        private static Map<String, Object> entitlement(UUID tenantId, String app, boolean enabled) {
            Map<String, Object> item = new HashMap<>();
            item.put("tenant_id", tenantId.toString());
            item.put("app_code", app);
            item.put("enabled", enabled);
            item.put("version", 1);
            return item;
        }

        /** Splits items into pages of {@code size}, with a next cursor on every full page. */
        private static List<SnapshotClient.Page> page(List<Map<String, Object>> items, int size) {
            List<SnapshotClient.Page> pages = new ArrayList<>();
            for (int i = 0; i < items.size(); i += size) {
                List<Map<String, Object>> slice = items.subList(i, Math.min(i + size, items.size()));
                boolean last = i + size >= items.size();
                pages.add(new SnapshotClient.Page(slice, last ? null : String.valueOf(slice.getLast().get("id"))));
            }
            return pages;
        }
    }

    /** Answers each stream's pages in order and records every call, so a test can assert the rebuild and its order. */
    private static final class RecordingTransport implements SnapshotClient.SnapshotTransport {
        private final Map<String, java.util.function.Supplier<List<SnapshotClient.Page>>> pages = new HashMap<>();
        private final Map<String, Integer> cursors = new HashMap<>();
        private final List<String> calls = new ArrayList<>();

        void on(String stream, java.util.function.Supplier<List<SnapshotClient.Page>> pageSupplier) {
            pages.put(stream, pageSupplier);
        }

        void reset() {
            calls.clear();
            cursors.clear();
        }

        @Override
        public String serviceToken() {
            return "test-token";
        }

        @Override
        public SnapshotClient.Page page(String stream, String after, int limit, String bearerToken) {
            calls.add(stream + "@" + after);
            List<SnapshotClient.Page> all = pages.get(stream).get();
            int index = after == null ? 0 : cursors.merge(stream, 1, Integer::sum);
            return all.get(Math.min(index, all.size() - 1));
        }
    }
}
