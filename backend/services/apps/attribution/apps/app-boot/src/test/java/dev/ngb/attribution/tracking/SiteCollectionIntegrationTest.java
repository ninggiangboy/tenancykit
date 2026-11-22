package dev.ngb.attribution.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.shared.redirect.ClickToken;
import dev.ngb.attribution.support.PostgresContainerConfig;
import dev.ngb.attribution.tracking.adapter.collect.persistence.JooqErasureStore;
import dev.ngb.attribution.tracking.adapter.collect.persistence.JooqPageViewStore;
import dev.ngb.attribution.tracking.application.collect.command.CollectPageViewsUseCase;
import dev.ngb.attribution.tracking.application.collect.port.ErasureStore;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The collection store against the real migrations: consent enforced at the storage boundary, a signature required,
 * sessions and bindings written, and an erasure that removes exactly one visitor's rows.
 */
class SiteCollectionIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);
    private static final Map<String, String> CLICK_KEYS = Map.of("k1", "test-click-secret");

    static {
        POSTGRES.start();
    }

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final TenantId otherTenant = new TenantId(UUID.randomUUID());

    private DSLContext dsl;
    private DSLContext ownerDsl;
    private UnitOfWork unitOfWork;
    private FixedTenantContext tenantContext;
    private JooqPageViewStore pageViews;
    private JooqErasureStore erasure;
    private CollectPageViewsUseCase collect;
    private final IdGenerator ids = UUID::randomUUID;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource owner = new PGSimpleDataSource();
        owner.setUrl(POSTGRES.getJdbcUrl());
        owner.setUser(POSTGRES.getUsername());
        owner.setPassword(POSTGRES.getPassword());
        ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
        ownerDsl.execute("drop schema if exists public cascade");
        ownerDsl.execute("create schema public");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("appDbPassword", PostgresContainerConfig.APP_PASSWORD))
                .load()
                .migrate();

        PGSimpleDataSource app = new PGSimpleDataSource();
        app.setUrl(POSTGRES.getJdbcUrl());
        app.setUser(PostgresContainerConfig.APP_ROLE);
        app.setPassword(PostgresContainerConfig.APP_PASSWORD);
        TransactionAwareDataSourceProxy proxied = new TransactionAwareDataSourceProxy(app);
        dsl = DSL.using(proxied, SQLDialect.POSTGRES);

        tenantContext = new FixedTenantContext();
        tenantContext.tenant = tenant;
        unitOfWork = new SpringUnitOfWork(
                new DataSourceTransactionManager(app),
                new TestScopeHook(tenantContext, new JdbcTemplate(proxied)));
        pageViews = new JooqPageViewStore(dsl);
        erasure = new JooqErasureStore(dsl);
        collect = new CollectPageViewsUseCase(unitOfWork, tenantContext, pageViews, ids, CLICK_KEYS);
    }

    @Test
    void noConsentStoresNoVisitorOrSessionIdentifier() {
        String token = ClickToken.sign(UUID.randomUUID(), "k1", "test-click-secret");

        collect.execute(List.of(incoming(token, "visitor-1", "session-1", "/landing")), false, false, Instant.now());

        var row = ownerDsl.fetchOne("select visitor_id, session_id, click_id from page_events where path = '/landing'");
        assertThat(row).isNotNull();
        assertThat(row.get("visitor_id", String.class)).isNull();
        assertThat(row.get("session_id", String.class)).isNull();
        assertThat(row.get("click_id")).isNotNull();
        // No cookie-setting instruction is the client's job; the server's job is that no identifier was stored.
        assertThat(ownerDsl.fetchCount(DSL.table("visitor_clicks"))).isZero();
        assertThat(ownerDsl.fetchCount(DSL.table("sessions"))).isZero();
    }

    @Test
    void consentStoresTheIdentifierAndBindsTheVisitorToTheClick() {
        UUID clickId = UUID.randomUUID();
        String token = ClickToken.sign(clickId, "k1", "test-click-secret");

        collect.execute(List.of(incoming(token, "visitor-1", "session-1", "/landing?utm=ignored#frag")), true, false,
                Instant.now());

        var row = ownerDsl.fetchOne("select visitor_id, session_id, click_id, path from page_events");
        assertThat(row.get("visitor_id", String.class)).isEqualTo("visitor-1");
        assertThat(row.get("path", String.class)).isEqualTo("/landing");
        assertThat(ownerDsl.fetchCount(DSL.table("visitor_clicks"))).isEqualTo(1);
        assertThat(ownerDsl.fetchCount(DSL.table("sessions"))).isEqualTo(1);
    }

    @Test
    void anEventWithAForgedSignatureIsNotStored() {
        String forged = "bm90LWEtY2xpY2s~azE~bm90LWEtc2lnbmF0dXJl";

        CollectPageViewsUseCase.Result result =
                collect.execute(List.of(incoming(forged, "v", "s", "/landing")), true, false, Instant.now());

        assertThat(result.stored()).isZero();
        assertThat(result.refused()).isEqualTo(1);
        assertThat(ownerDsl.fetchCount(DSL.table("page_events"))).isZero();
    }

    @Test
    void twoPageViewsInOneSessionIncrementThePageCountAndTwoClicksBindTwoRows() {
        String first = ClickToken.sign(UUID.randomUUID(), "k1", "test-click-secret");
        String second = ClickToken.sign(UUID.randomUUID(), "k1", "test-click-secret");
        collect.execute(List.of(incoming(first, "visitor-1", "session-1", "/a")), true, false, Instant.now());
        collect.execute(
                List.of(incoming(first, "visitor-1", "session-1", "/b"), incoming(second, "visitor-1", "session-1", "/c")),
                true, false, Instant.now());

        assertThat(ownerDsl.fetchCount(DSL.table("page_events"))).isEqualTo(3);
        Integer count = ownerDsl.fetchOne("select page_count from sessions where session_id = 'session-1'")
                .get("page_count", Integer.class);
        assertThat(count).isEqualTo(3);
        assertThat(ownerDsl.fetchCount(DSL.table("visitor_clicks"))).isEqualTo(2);
    }

    @Test
    void erasureRemovesExactlyThatVisitorsRowsAndNoOther() {
        String tokenA = ClickToken.sign(UUID.randomUUID(), "k1", "test-click-secret");
        String tokenB = ClickToken.sign(UUID.randomUUID(), "k1", "test-click-secret");
        collect.execute(List.of(incoming(tokenA, "visitor-a", "session-a", "/a")), true, false, Instant.now());
        collect.execute(List.of(incoming(tokenB, "visitor-b", "session-b", "/b")), true, false, Instant.now());

        ErasureStore.Removed removed = unitOfWork.transactional(() -> erasure.erase("visitor-a"));

        assertThat(removed.pageViews()).isEqualTo(1);
        assertThat(removed.sessions()).isEqualTo(1);
        assertThat(removed.bindings()).isEqualTo(1);
        // The other visitor's page view, session and binding remain.
        assertThat(ownerDsl.fetchCount(DSL.table("page_events"))).isEqualTo(1);
        assertThat(ownerDsl.fetchCount(DSL.table("sessions"))).isEqualTo(1);
        assertThat(ownerDsl.fetchCount(DSL.table("visitor_clicks"))).isEqualTo(1);
    }

    @Test
    void aReplayedBatchStoresItsPageViewsOnce() {
        String token = ClickToken.sign(UUID.randomUUID(), "k1", "test-click-secret");
        CollectPageViewsUseCase.Incoming event = incoming(token, "visitor-1", "session-1", "/a");

        collect.execute(List.of(event), true, false, Instant.now());
        CollectPageViewsUseCase.Result replay = collect.execute(List.of(event), true, false, Instant.now());

        assertThat(replay.stored()).isZero();
        assertThat(ownerDsl.fetchCount(DSL.table("page_events"))).isEqualTo(1);
    }

    private static CollectPageViewsUseCase.Incoming incoming(
            String clickToken, String visitorId, String sessionId, String path) {
        return new CollectPageViewsUseCase.Incoming(
                UUID.randomUUID().toString(), clickToken, visitorId, sessionId, path, Instant.now().toString());
    }

    private static final class FixedTenantContext implements TenantContext {

        private TenantId tenant;

        @Override
        public RequestScope current() {
            return RequestScope.tenant(tenant);
        }

        @Override
        public <T> T callAs(RequestScope scope, Supplier<T> work) {
            TenantId previous = tenant;
            tenant = ((RequestScope.Tenant) scope).tenantId();
            try {
                return work.get();
            } finally {
                tenant = previous;
            }
        }
    }

    private record TestScopeHook(TenantContext tenantContext, JdbcTemplate jdbc) implements TransactionScopeHook {

        @Override
        public RequestScope currentScope() {
            return tenantContext.current();
        }

        @Override
        public void onNewTransaction(RequestScope scope) {
            jdbc.queryForObject(
                    "select set_config('app.tenant_id', ?, true)", String.class, scope.dataTenantId().toString());
        }
    }
}
