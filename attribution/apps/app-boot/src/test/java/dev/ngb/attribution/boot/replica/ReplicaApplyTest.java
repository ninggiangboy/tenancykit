package dev.ngb.attribution.boot.replica;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.inbox.JooqEventInbox;
import dev.ngb.core.replica.ReplicaWriter;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import dev.ngb.attribution.platform.adapter.platform.persistence.JooqTenantDirectory;
import dev.ngb.attribution.platform.adapter.platform.persistence.JooqUserDirectory;
import dev.ngb.attribution.platform.application.platform.service.TenantDirectoryService;
import dev.ngb.attribution.platform.application.platform.service.UserDirectoryService;
import dev.ngb.attribution.support.PostgresContainerConfig;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The guarded upsert, the inbox dedupe and the tenant directory, against the real migrations. */
class ReplicaApplyTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);

    static {
        POSTGRES.start();
    }

    private DSLContext dsl;
    private UnitOfWork unitOfWork;
    private ReplicaWriter writer;
    private TenantDirectoryService tenants;
    private UserDirectoryService users;
    private PlatformEventsHandler handler;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
        dsl.execute("drop schema if exists public cascade");
        dsl.execute("create schema public");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("appDbPassword", "unused"))
                .load()
                .migrate();
        unitOfWork = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource), TransactionScopeHook.none());
        writer = new ReplicaWriter(dsl);
        tenants = new TenantDirectoryService(new JooqTenantDirectory(dsl));
        users = new UserDirectoryService(new JooqUserDirectory(dsl));
        handler = new PlatformEventsHandler(writer, tenants, users, List.of());
    }

    @Test
    void aNewerEventWinsAndAnOlderOneChangesNothing() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        writer.apply("tenant.changed", tenant(tenantId, "acme", "Acme"));
        handler.handle(envelope("user-access.changed", tenantId, user(userId, tenantId, List.of("a"), 5)));

        handler.handle(envelope("user-access.changed", tenantId, user(userId, tenantId, List.of("b"), 3)));

        var row = dsl.fetchOne("select version, permissions from user_grants where user_id = ?", userId);
        assertThat(row.get("version", Long.class)).isEqualTo(5L);
        assertThat(row.get("permissions", String[].class)).containsExactly("a");
    }

    @Test
    void aRepeatedEnvelopeIsRecordedOnceAndChangesNothing() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        writer.apply("tenant.changed", tenant(tenantId, "acme", "Acme"));
        EventEnvelope event = envelope("user-access.changed", tenantId, user(userId, tenantId, List.of("a"), 1));
        JooqEventInbox inbox = new JooqEventInbox(dsl);

        deliver(inbox, event);
        deliver(inbox, event);

        assertThat(dsl.fetchCount(DSL.table(DSL.name("event_inbox")))).isEqualTo(1);
        assertThat(dsl.fetchCount(DSL.table(DSL.name("user_grants")))).isEqualTo(1);
    }

    @Test
    void activeTenantsExcludeThePlatformPseudoTenant() {
        UUID platform = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID acme = UUID.randomUUID();
        writer.apply("tenant.changed", tenant(platform, "platform", "Platform"));
        writer.apply("tenant.changed", tenant(acme, "acme", "Acme"));

        assertThat(tenants.activeTenantIds()).containsExactly(new TenantId(acme));
    }

    @Test
    void anEntitlementTransitionIsReadableBeforeTheUpsert() {
        UUID tenantId = UUID.randomUUID();
        writer.apply("tenant.changed", tenant(tenantId, "acme", "Acme"));
        writer.apply("entitlement.changed", entitlement(tenantId, true, 1));

        assertThat(tenants.entitlementEnabled(new TenantId(tenantId), "attribution")).contains(true);
        writer.apply("entitlement.changed", entitlement(tenantId, false, 2));
        assertThat(tenants.entitlementEnabled(new TenantId(tenantId), "attribution")).contains(false);
    }

    private void deliver(JooqEventInbox inbox, EventEnvelope event) {
        unitOfWork.transactional(() -> {
            if (inbox.firstTime(PlatformEventsHandler.CONSUMER, event)) {
                handler.handle(event);
            }
            return null;
        });
    }

    private static EventEnvelope envelope(String type, UUID tenantId, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), new TenantId(tenantId), type, "test", Instant.now(), payload);
    }

    private static Map<String, Object> user(UUID userId, UUID tenantId, List<String> permissions, long version) {
        Map<String, Object> data = new HashMap<>();
        data.put("user_id", userId.toString());
        data.put("tenant_id", tenantId.toString());
        data.put("scope", "TENANT");
        data.put("keycloak_subject", "kc-" + userId);
        data.put("email", "a@example.com");
        data.put("display_name", "A");
        data.put("status", "ACTIVE");
        data.put("permissions", permissions);
        data.put("version", version);
        return data;
    }

    private static Map<String, Object> tenant(UUID id, String code, String name) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id.toString());
        data.put("code", code);
        data.put("name", name);
        data.put("status", "ACTIVE");
        data.put("created_at", Instant.now().toString());
        data.put("version", 1);
        return data;
    }

    private static Map<String, Object> entitlement(UUID tenantId, boolean enabled, long version) {
        Map<String, Object> data = new HashMap<>();
        data.put("tenant_id", tenantId.toString());
        data.put("app_code", "attribution");
        data.put("enabled", enabled);
        data.put("version", version);
        return data;
    }
}
