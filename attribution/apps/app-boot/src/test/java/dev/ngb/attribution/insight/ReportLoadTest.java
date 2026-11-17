package dev.ngb.attribution.insight;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.attribution.adapter.conversion.persistence.JooqConversionRollupSource;
import dev.ngb.attribution.insight.adapter.report.persistence.JooqReportReadRepository;
import dev.ngb.attribution.insight.adapter.rollup.persistence.JooqRollupStateReader;
import dev.ngb.attribution.insight.application.report.query.BreakdownView;
import dev.ngb.attribution.insight.application.report.query.CohortQuery;
import dev.ngb.attribution.insight.application.report.query.Dimension;
import dev.ngb.attribution.insight.application.report.query.FunnelQuery;
import dev.ngb.attribution.insight.application.report.query.Granularity;
import dev.ngb.attribution.insight.application.report.query.Metric;
import dev.ngb.attribution.insight.application.report.query.ReportFilter;
import dev.ngb.attribution.insight.application.report.query.ReportQueries;
import dev.ngb.attribution.insight.application.report.query.ReportQuery;
import dev.ngb.attribution.insight.application.report.query.ReportRange;
import dev.ngb.attribution.insight.application.report.query.TimeSeriesView;
import dev.ngb.attribution.insight.application.report.query.TrafficClassFilter;
import dev.ngb.attribution.links.adapter.campaign.persistence.JooqCampaignPolicyReader;
import dev.ngb.attribution.links.adapter.link.persistence.JooqLinkMetadataReader;
import dev.ngb.attribution.spend.adapter.cost.persistence.JooqCostReader;
import dev.ngb.attribution.support.PostgresContainerConfig;
import dev.ngb.attribution.tracking.adapter.traffic.persistence.JooqTrafficSource;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The seeded report load test: a large click population, the rollups built from it, and every report measured at p95.
 *
 * <p>It lives outside the normal build because it seeds millions of rows. Run it with
 * {@code ./gradlew :apps:app-boot:reportLoad}; the size is configurable with {@code -Dattribution.load.clicks}. The
 * target is the plan's: every report answers at p95 ≤ 500 ms.
 */
@Tag("loadtest")
class ReportLoadTest {

    private static final int CLICKS = Integer.getInteger("attribution.load.clicks", 5_000_000);
    private static final int CONVERSIONS = Math.max(1_000, CLICKS / 50);
    private static final int ITERATIONS = 10;
    private static final long P95_NANOS = 500_000_000L;

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);

    static {
        POSTGRES.start();
    }

    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Test
    void everyReportAnswersAtP95Within500ms() {
        PGSimpleDataSource owner = new PGSimpleDataSource();
        owner.setUrl(POSTGRES.getJdbcUrl());
        owner.setUser(POSTGRES.getUsername());
        owner.setPassword(POSTGRES.getPassword());
        DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
        ownerDsl.execute("drop schema if exists public cascade");
        ownerDsl.execute("create schema public");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("appDbPassword", PostgresContainerConfig.APP_PASSWORD))
                .load()
                .migrate();

        UUID campaign = UUID.randomUUID();
        UUID link = UUID.randomUUID();
        UUID goal = UUID.randomUUID();
        seed(ownerDsl, campaign, link, goal);

        PGSimpleDataSource app = new PGSimpleDataSource();
        app.setUrl(POSTGRES.getJdbcUrl());
        app.setUser(PostgresContainerConfig.APP_ROLE);
        app.setPassword(PostgresContainerConfig.APP_PASSWORD);
        TransactionAwareDataSourceProxy proxied = new TransactionAwareDataSourceProxy(app);
        DSLContext dsl = DSL.using(proxied, SQLDialect.POSTGRES);
        FixedTenantContext tenantContext = new FixedTenantContext();
        tenantContext.tenant = tenant;
        UnitOfWork unitOfWork = new SpringUnitOfWork(
                new DataSourceTransactionManager(app),
                new TestScopeHook(tenantContext, new JdbcTemplate(proxied)));

        ReportQueries reports = new ReportQueries(
                unitOfWork,
                new JooqReportReadRepository(dsl, new JooqTrafficSource(dsl), new JooqConversionRollupSource(dsl)),
                new JooqRollupStateReader(dsl),
                new JooqCostReader(dsl),
                new JooqCampaignPolicyReader(dsl),
                new JooqLinkMetadataReader(dsl));

        Instant now = Instant.now();
        ReportRange range = new ReportRange(now.minus(30, ChronoUnit.DAYS), now);

        assertP95("timeseries", () -> reports.timeseries(new ReportQuery(
                range, Granularity.DAY,
                Set.of(Metric.CLICKS, Metric.PAGE_VIEWS, Metric.SESSIONS, Metric.VISITOR_DAYS, Metric.CONVERSIONS,
                        Metric.CREDITED_VALUE),
                ReportFilter.empty(), TrafficClassFilter.human())).points());
        assertP95("breakdown-link", () -> reports.breakdown(new ReportQuery(
                range, Granularity.DAY, Set.of(Metric.CLICKS, Metric.PAGE_VIEWS, Metric.CONVERSIONS),
                ReportFilter.empty(), TrafficClassFilter.human()), Dimension.LINK).rows());
        assertP95("breakdown-device", () -> reports.breakdown(new ReportQuery(
                range, Granularity.DAY, Set.of(Metric.CLICKS), ReportFilter.empty(), TrafficClassFilter.human()),
                Dimension.DEVICE).rows());
        assertP95("funnel", () -> reports.funnel(new FunnelQuery(
                range.from(), range.to(), null, link, null, TrafficClassFilter.human())));
        assertP95("cohorts", () -> reports.cohorts(new CohortQuery(campaign, range.from(), range.to(), 12)).rows());
        assertP95("costs", () -> reports.costs(range, campaign, "campaign").rows());
    }

    private static void assertP95(String name, Supplier<?> report) {
        for (int warmup = 0; warmup < 3; warmup++) {
            report.get();
        }
        List<Long> timings = new ArrayList<>(ITERATIONS);
        for (int run = 0; run < ITERATIONS; run++) {
            long start = System.nanoTime();
            report.get();
            timings.add(System.nanoTime() - start);
        }
        Collections.sort(timings);
        long p95 = timings.get((int) Math.ceil(0.95 * timings.size()) - 1);
        assertThat(p95).as(name + " p95 is " + p95 + " ns").isLessThanOrEqualTo(P95_NANOS);
    }

    private void seed(DSLContext owner, UUID campaign, UUID link, UUID goal) {
        owner.execute(
                "insert into campaigns (id, tenant_id, name, slug, status, attribution_model, window_days, currency,"
                        + " created_at, updated_at) values (?, ?, 'Load', 'load-campaign', 'ACTIVE', 'LAST', 30, 'USD',"
                        + " now(), now())",
                campaign, tenant.value());
        owner.execute(
                "insert into links (id, tenant_id, campaign_id, slug, kind, status, utm, created_at, updated_at)"
                        + " values (?, ?, ?, 'load-link', 'STANDARD', 'ACTIVE', '{}', now(), now())",
                link, tenant.value(), campaign);
        owner.execute(
                "insert into goals (id, tenant_id, key, name, status, created_at, updated_at)"
                        + " values (?, ?, 'purchase', 'Purchase', 'ACTIVE', now(), now())",
                goal, tenant.value());

        // A deployment keeps a window of indexed day partitions; create the load window so the raw funnel reads them
        // rather than the unindexed default partition, which is only an alert path.
        owner.execute("select attribution_ensure_partitions('click_events', current_date - 31, 40)");
        owner.execute("select attribution_ensure_partitions('page_events', current_date - 31, 40)");

        owner.execute(
                "insert into click_events (event_id, occurred_at, tenant_id, click_id, link_id, campaign_id,"
                        + " device_class, browser_family, country, entry, referrer_domain, traffic_class, visitor_hash)"
                        + " select gen_random_uuid(), now() - (random() * interval '30 days'), cast(? as uuid),"
                        + " gen_random_uuid(), cast(? as uuid), cast(? as uuid),"
                        + " (array['DESKTOP','MOBILE','TABLET'])[1 + floor(random() * 3)], 'Chrome',"
                        + " (array['US','VN','GB','DE','FR'])[1 + floor(random() * 5)], 'google', 'example.com',"
                        + " 'HUMAN', md5(random()::text) from generate_series(1, ?)",
                tenant.value(), link, campaign, CLICKS);

        owner.execute(
                "insert into page_events (event_id, occurred_at, tenant_id, click_id, session_id, visitor_id, path,"
                        + " traffic_class) select gen_random_uuid(), occurred_at + interval '1 minute', tenant_id,"
                        + " click_id, md5(event_id::text), md5(visitor_hash), '/landing', 'HUMAN' from click_events"
                        + " where tenant_id = cast(? as uuid) and (abs(hashtext(event_id::text)) % 10) = 0",
                tenant.value());

        owner.execute(
                "insert into rollup_traffic_hourly (tenant_id, bucket, link_id, campaign_id, device_class, country,"
                        + " entry, traffic_class, clicks, page_views, sessions, visitor_days)"
                        + " select tenant_id, date_trunc('hour', occurred_at), link_id, campaign_id, device_class,"
                        + " country, entry, traffic_class, count(*), 0, 0, count(distinct visitor_hash)"
                        + " from click_events where tenant_id = cast(? as uuid) group by 1, 2, 3, 4, 5, 6, 7, 8",
                tenant.value());

        owner.execute(
                "insert into conversions (id, tenant_id, goal_id, value, currency, occurred_at, source,"
                        + " idempotency_key, credit_status, owning_campaign_id, touch_chain, computed_at, created_at)"
                        + " select gen_random_uuid(), cast(? as uuid), cast(? as uuid), 100.00, 'USD',"
                        + " now() - (random() * interval '30 days'), 'POSTBACK', 'load-' || g, 'COMPUTED',"
                        + " cast(? as uuid), jsonb_build_array(jsonb_build_object('clickId', gen_random_uuid()::text,"
                        + " 'linkId', cast(? as text), 'campaignId', cast(? as text), 'at',"
                        + " (now() - interval '1 day')::text, 'human', true)), now(), now()"
                        + " from generate_series(1, ?) g",
                tenant.value(), goal, campaign, link, campaign, CONVERSIONS);
        owner.execute(
                "insert into conversion_credits (tenant_id, conversion_id, click_id, link_id, campaign_id, model,"
                        + " weight, credit) select tenant_id, id, (touch_chain->0->>'clickId')::uuid,"
                        + " cast(? as uuid), cast(? as uuid), 'LAST', 1, 100.00 from conversions"
                        + " where tenant_id = cast(? as uuid)",
                link, campaign, tenant.value());
        owner.execute(
                "insert into rollup_conversions_daily (tenant_id, day, campaign_id, link_id, goal_id, model, currency,"
                        + " conversions, credited_value, multi_touch_conversions)"
                        + " select tenant_id, (occurred_at at time zone 'UTC')::date, owning_campaign_id,"
                        + " cast(? as uuid), goal_id, 'LAST', 'USD', count(*), 100.00 * count(*), 0"
                        + " from conversions where tenant_id = cast(? as uuid) group by 1, 2, 3, 5",
                link, tenant.value());

        for (int day = 0; day < 30; day++) {
            owner.execute(
                    "insert into spend_entries (id, tenant_id, campaign_id, day, source, amount, currency, updated_at)"
                            + " values (gen_random_uuid(), cast(? as uuid), cast(? as uuid), current_date - ?,"
                            + " 'MANUAL', 50.00, 'USD', now())",
                    tenant.value(), campaign, day);
        }
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
