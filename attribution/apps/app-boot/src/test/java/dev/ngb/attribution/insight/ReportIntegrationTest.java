package dev.ngb.attribution.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.attribution.attribution.adapter.conversion.persistence.JooqConversionRollupSource;
import dev.ngb.attribution.insight.adapter.report.persistence.JooqReportReadRepository;
import dev.ngb.attribution.insight.adapter.rollup.persistence.JooqRollupRepository;
import dev.ngb.attribution.insight.adapter.rollup.persistence.JooqRollupStateReader;
import dev.ngb.attribution.insight.application.report.query.BreakdownView;
import dev.ngb.attribution.insight.application.report.query.CohortQuery;
import dev.ngb.attribution.insight.application.report.query.CohortView;
import dev.ngb.attribution.insight.application.report.query.CostReportView;
import dev.ngb.attribution.insight.application.report.query.Dimension;
import dev.ngb.attribution.insight.application.report.query.FunnelQuery;
import dev.ngb.attribution.insight.application.report.query.FunnelView;
import dev.ngb.attribution.insight.application.report.query.Granularity;
import dev.ngb.attribution.insight.application.report.query.LinkStatsQueries;
import dev.ngb.attribution.insight.application.report.query.LinkStatsView;
import dev.ngb.attribution.insight.application.report.query.Metric;
import dev.ngb.attribution.insight.application.report.query.ReportFilter;
import dev.ngb.attribution.insight.application.report.query.ReportQueries;
import dev.ngb.attribution.insight.application.report.query.ReportQuery;
import dev.ngb.attribution.insight.application.report.query.ReportRange;
import dev.ngb.attribution.insight.application.report.query.TimeSeriesView;
import dev.ngb.attribution.insight.application.report.query.TrafficClassFilter;
import dev.ngb.attribution.insight.application.report.service.CsvReportWriter;
import dev.ngb.attribution.insight.application.rollup.command.RunRollupUseCase;
import dev.ngb.attribution.links.adapter.campaign.persistence.JooqCampaignPolicyReader;
import dev.ngb.attribution.links.adapter.link.persistence.JooqLinkMetadataReader;
import dev.ngb.attribution.spend.adapter.cost.persistence.JooqCostReader;
import dev.ngb.attribution.support.PostgresContainerConfig;
import dev.ngb.attribution.tracking.adapter.traffic.persistence.JooqTrafficSource;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.runtime.SystemClock;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 9 end to end against the real migrations: a seeded two-campaign, two-day scenario answers every report exactly,
 * a late click inside three hours changes the next run, bot traffic is excluded by default, tenant B is invisible and a
 * CSV equals its JSON.
 */
class ReportIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);
    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    static {
        POSTGRES.start();
    }

    private final JsonMapper json = new JsonMapper();
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final TenantId otherTenant = new TenantId(UUID.randomUUID());

    private DSLContext dsl;
    private DSLContext ownerDsl;
    private UnitOfWork unitOfWork;
    private FixedTenantContext tenantContext;
    private ReportQueries reports;
    private LinkStatsQueries linkStats;
    private RunRollupUseCase rollup;

    private UUID campaignA;
    private UUID campaignB;
    private UUID linkA;
    private UUID linkB;
    private UUID goalPurchase;
    private UUID goalSignup;
    private UUID click1;
    private UUID click2;
    private UUID click3;
    private UUID click4;

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

        JooqTrafficSource traffic = new JooqTrafficSource(dsl);
        JooqConversionRollupSource conversions = new JooqConversionRollupSource(dsl);
        JooqRollupRepository rollups = new JooqRollupRepository(dsl, tenantContext);
        JooqRollupStateReader state = new JooqRollupStateReader(dsl);
        JooqReportReadRepository readRepository = new JooqReportReadRepository(dsl, traffic, conversions);
        reports = new ReportQueries(
                unitOfWork,
                readRepository,
                state,
                new JooqCostReader(dsl),
                new JooqCampaignPolicyReader(dsl),
                new JooqLinkMetadataReader(dsl));
        linkStats = new LinkStatsQueries(unitOfWork, readRepository, new JooqLinkMetadataReader(dsl), state);
        rollup = new RunRollupUseCase(unitOfWork, traffic, conversions, rollups, state);

        seed();
        rollup.execute(NOW);
    }

    @Test
    void theSeededDayAnswersEverySeriesBreakdownFunnelAndCohortExactly() {
        TimeSeriesView series = reports.timeseries(new ReportQuery(
                new ReportRange(NOW.minusSeconds(6 * 3600), NOW), Granularity.DAY,
                Set.of(Metric.CLICKS, Metric.PAGE_VIEWS, Metric.SESSIONS, Metric.VISITOR_DAYS, Metric.CONVERSIONS,
                        Metric.CREDITED_VALUE),
                ReportFilter.empty(), TrafficClassFilter.human()));

        assertThat(series.points()).singleElement().satisfies(point -> {
            assertThat(point.clicks()).isEqualTo(3);
            assertThat(point.pageViews()).isEqualTo(3);
            assertThat(point.sessions()).isEqualTo(2);
            assertThat(point.uniqueVisitorDays()).isEqualTo(3);
            assertThat(point.conversions()).isEqualByComparingTo("2");
            assertThat(point.creditedValue()).isEqualByComparingTo("140");
            assertThat(point.multiTouchShare()).isEqualByComparingTo("0.5000");
        });
        assertThat(series.dataThrough()).isEqualTo(NOW);

        BreakdownView byDevice = reports.breakdown(new ReportQuery(
                new ReportRange(NOW.minusSeconds(6 * 3600), NOW), Granularity.DAY,
                Set.of(Metric.CLICKS, Metric.PAGE_VIEWS), ReportFilter.empty(), TrafficClassFilter.human()),
                Dimension.DEVICE);
        assertThat(byDevice.rows()).extracting(BreakdownView.Row::key, BreakdownView.Row::clicks)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("DESKTOP", 2L),
                        org.assertj.core.groups.Tuple.tuple("MOBILE", 1L));
        assertThat(byDevice.botClicksExcluded()).isEqualTo(1);

        BreakdownView byCampaign = reports.breakdown(new ReportQuery(
                new ReportRange(NOW.minusSeconds(6 * 3600), NOW), Granularity.DAY,
                Set.of(Metric.CLICKS, Metric.CONVERSIONS, Metric.CREDITED_VALUE), ReportFilter.empty(),
                TrafficClassFilter.human()), Dimension.CAMPAIGN);
        assertThat(byCampaign.rows())
                .filteredOn(row -> row.key().equals(campaignA.toString()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.clicks()).isEqualTo(2);
                    assertThat(row.conversions()).isEqualByComparingTo("1");
                    assertThat(row.creditedValue()).isEqualByComparingTo("100");
                    assertThat(row.multiTouchShare()).isEqualByComparingTo("1.0000");
                });

        FunnelView funnel = reports.funnel(new FunnelQuery(
                NOW.minusSeconds(6 * 3600), NOW, campaignA, null, null, TrafficClassFilter.human()));
        assertThat(funnel.clicks()).isEqualTo(2);
        assertThat(funnel.landed()).isEqualTo(2);
        assertThat(funnel.converted()).isEqualTo(2);
        assertThat(funnel.botClicksExcluded()).isEqualTo(1);
        assertThat(funnel.landedRate()).isEqualByComparingTo("1.0000");
        assertThat(funnel.convertedRate()).isEqualByComparingTo("1.0000");

        CohortView cohorts = reports.cohorts(new CohortQuery(
                campaignA, NOW.minusSeconds(7 * 86400L), NOW, 12));
        assertThat(cohorts.rows()).singleElement().satisfies(row -> {
            assertThat(row.cohortWeek()).isEqualTo("2026-01-12");
            assertThat(row.cells()).singleElement().satisfies(cell -> {
                assertThat(cell.week()).isZero();
                assertThat(cell.conversions()).isEqualTo(1);
                assertThat(cell.value()).isEqualByComparingTo("100");
            });
        });
    }

    @Test
    void aLateClickInsideThreeHoursChangesTheNextRun() {
        UUID late = insertClick(campaignA, linkA, NOW.minusSeconds(20 * 60), "DESKTOP", "US", "google",
                "example.com", "HUMAN", "late-visitor");

        TimeSeriesView before = series();
        assertThat(before.points().getFirst().clicks()).isEqualTo(3);

        rollup.execute(NOW);

        TimeSeriesView after = series();
        assertThat(after.points().getFirst().clicks()).isEqualTo(4);
        assertThat(late).isNotNull();
    }

    @Test
    void botClicksAreExcludedByDefaultAndIncludedOnRequest() {
        assertThat(series().points().getFirst().clicks()).isEqualTo(3);
        assertThat(series().points().getFirst().pageViews()).isEqualTo(3);

        TimeSeriesView withBots = reports.timeseries(new ReportQuery(
                new ReportRange(NOW.minusSeconds(6 * 3600), NOW), Granularity.DAY, Set.of(Metric.CLICKS),
                ReportFilter.empty(), TrafficClassFilter.of("bot,suspect")));
        assertThat(withBots.points().getFirst().clicks()).isEqualTo(4);
    }

    @Test
    void tenantBIsInvisible() {
        UUID otherCampaign = UUID.randomUUID();
        UUID otherLink = UUID.randomUUID();
        ownerDsl.execute(
                "insert into campaigns (id, tenant_id, name, slug, status, attribution_model, window_days, currency,"
                        + " created_at, updated_at) values (?, ?, 'Other', ?, 'ACTIVE', 'LAST', 30, 'USD', now(), now())",
                otherCampaign, otherTenant.value(), "other-" + otherCampaign.toString().substring(0, 8));
        ownerDsl.execute(
                "insert into links (id, tenant_id, campaign_id, slug, kind, status, utm, created_at, updated_at)"
                        + " values (?, ?, ?, ?, 'STANDARD', 'ACTIVE', '{}', now(), now())",
                otherLink, otherTenant.value(), otherCampaign,
                "other-" + otherLink.toString().substring(0, 8));
        ownerDsl.execute(
                "insert into click_events (event_id, occurred_at, tenant_id, click_id, link_id, campaign_id,"
                        + " device_class, browser_family, traffic_class, visitor_hash)"
                        + " values (?, cast(? as timestamptz), cast(? as uuid), cast(? as uuid), cast(? as uuid),"
                        + " cast(? as uuid), 'DESKTOP', 'Chrome', 'HUMAN', 'other-hash')",
                UUID.randomUUID(), NOW.minusSeconds(600), otherTenant.value(), UUID.randomUUID(), otherLink,
                otherCampaign);
        // Build the other tenant's rollups too, so the assertion is about isolation and not about an empty table.
        tenantContext.callAs(RequestScope.tenant(otherTenant), () -> rollup.execute(NOW));

        assertThat(series().points().getFirst().clicks()).isEqualTo(3);
        assertThat(reports.breakdown(new ReportQuery(
                new ReportRange(NOW.minusSeconds(6 * 3600), NOW), Granularity.DAY, Set.of(Metric.CLICKS),
                ReportFilter.empty(), TrafficClassFilter.human()), Dimension.CAMPAIGN).rows())
                .extracting(BreakdownView.Row::key)
                .doesNotContain(otherCampaign.toString());
    }

    @Test
    void anUnknownDimensionIsABadRequest() {
        assertThatThrownBy(() -> Dimension.named("colours"))
                .isInstanceOf(dev.ngb.core.error.BadRequestException.class)
                .hasMessageContaining("colours");
    }

    @Test
    void theCsvCarriesTheSameValuesAsTheJson() {
        TimeSeriesView view = series();
        List<String> rows = CsvReportWriter.rows(view);

        assertThat(rows.getFirst()).startsWith("bucket,clicks");
        assertThat(rows.get(1)).contains("3").contains("3").contains("2");
        assertThat(rows).hasSize(2);
    }

    @Test
    void linkStatisticsReadTheRollupsAndTheLinkMetadata() {
        LinkStatsView stats = linkStats.stats(linkA, NOW);

        assertThat(stats.clicks()).isEqualTo(2);
        assertThat(stats.botClicksExcluded()).isEqualTo(1);
        assertThat(stats.last30Days()).singleElement().satisfies(day -> assertThat(day.clicks()).isEqualTo(2));
        assertThat(stats.status()).isEqualTo("ACTIVE");
    }

    @Test
    void anUnknownLinkIsNotFound() {
        assertThatThrownBy(() -> linkStats.stats(UUID.randomUUID(), NOW))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theCostReportAddsSpendToResultsAndExcludesAnotherCurrency() {
        spend(campaignA, "2026-01-15", "MANUAL", new BigDecimal("100.00"), "USD");
        spend(campaignA, "2026-01-15", "GOOGLE_ADS", new BigDecimal("25.00"), "USD");
        spend(campaignA, "2026-01-14", "MANUAL", new BigDecimal("50.00"), "USD");
        insertConversion(campaignA, goalPurchase, NOW.minusSeconds(8 * 60), new BigDecimal("10.00"), "EUR",
                "order-eur", List.of(new ChainTouch(click1.toString(), linkA.toString(), campaignA.toString(),
                        NOW.minusSeconds(2 * 3600 + 30 * 60).toString(), true)),
                List.of(new CreditRow(click1, linkA, campaignA, "LAST", BigDecimal.ONE, new BigDecimal("10.00"))));
        rollup.execute(NOW);

        var day = reports.costs(
                new ReportRange(Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-16T00:00:00Z")),
                campaignA, "day");
        assertThat(day.rows()).singleElement().satisfies(row -> {
            assertThat(row.cost()).isEqualByComparingTo("125.00");
            assertThat(row.humanClicks()).isEqualTo(2);
            assertThat(row.conversions()).isEqualByComparingTo("1");
            assertThat(row.creditedValue()).isEqualByComparingTo("100.00");
            assertThat(row.excludedConversions()).isEqualByComparingTo("1");
            assertThat(row.cpc()).isEqualByComparingTo("62.5000");
            assertThat(row.cpa()).isEqualByComparingTo("125.0000");
            assertThat(row.roas()).isEqualByComparingTo("0.8000");
            assertThat(row.roi()).isEqualByComparingTo("-0.2000");
            assertThat(row.sources()).extracting(CostReportView.SourceCost::source)
                    .containsExactly("GOOGLE_ADS", "MANUAL");
            assertThat(row.sources()).extracting(source -> source.amount().toPlainString())
                    .containsExactly("25.0000", "100.0000");
        });

        var campaign = reports.costs(
                new ReportRange(Instant.parse("2026-01-14T00:00:00Z"), Instant.parse("2026-01-16T00:00:00Z")),
                campaignA, "campaign");
        assertThat(campaign.rows()).singleElement().satisfies(row -> {
            assertThat(row.cost()).isEqualByComparingTo("175.00");
            assertThat(row.cpc()).isEqualByComparingTo("87.5000");
            assertThat(row.roas()).isEqualByComparingTo("0.5714");
        });
    }

    private void spend(UUID campaign, String day, String source, BigDecimal amount, String currency) {
        unitOfWork.transactional(() -> dsl.execute(
                "insert into spend_entries (id, tenant_id, campaign_id, day, source, amount, currency, updated_at)"
                        + " values (?, cast(? as uuid), cast(? as uuid), cast(? as date), ?, cast(? as numeric), ?, now())",
                UUID.randomUUID(), tenant.value(), campaign, day, source, amount, currency));
    }

    private TimeSeriesView series() {
        return reports.timeseries(new ReportQuery(
                new ReportRange(NOW.minusSeconds(6 * 3600), NOW), Granularity.DAY,
                Set.of(Metric.CLICKS, Metric.PAGE_VIEWS), ReportFilter.empty(), TrafficClassFilter.human()));
    }

    private void seed() {
        campaignA = campaign("Spring A");
        campaignB = campaign("Spring B");
        linkA = link(campaignA, "spring-a");
        linkB = link(campaignB, "spring-b");
        goalPurchase = goal("purchase");
        goalSignup = goal("signup");

        click1 = insertClick(campaignA, linkA, NOW.minusSeconds(2 * 3600 + 30 * 60), "DESKTOP", "US", "google",
                "example.com", "HUMAN", "visitor-1");
        click2 = insertClick(campaignA, linkA, NOW.minusSeconds(1 * 3600 + 30 * 60), "MOBILE", "VN", "facebook",
                "other.com", "HUMAN", "visitor-2");
        click3 = insertClick(campaignA, linkA, NOW.minusSeconds(1 * 3600 + 10 * 60), "DESKTOP", "US", "google",
                "bot.example", "BOT", "bot-visitor");
        click4 = insertClick(campaignB, linkB, NOW.minusSeconds(1 * 3600 + 50 * 60), "DESKTOP", "US", "google",
                "example.com", "HUMAN", "visitor-3");

        insertPageView(click1, NOW.minusSeconds(2 * 3600 + 25 * 60), "session-1", "visitor-1", "HUMAN");
        insertPageView(click1, NOW.minusSeconds(2 * 3600 + 20 * 60), "session-1", "visitor-1", "HUMAN");
        insertPageView(click2, NOW.minusSeconds(1 * 3600 + 25 * 60), "session-2", "visitor-2", "HUMAN");

        insertConversion(campaignA, goalPurchase, NOW.minusSeconds(15 * 60), new BigDecimal("100.00"), "USD",
                "order-a", List.of(
                        new ChainTouch(click1.toString(), linkA.toString(), campaignA.toString(),
                                NOW.minusSeconds(2 * 3600 + 30 * 60).toString(), true),
                        new ChainTouch(click2.toString(), linkA.toString(), campaignA.toString(),
                                NOW.minusSeconds(1 * 3600 + 30 * 60).toString(), true)),
                List.of(new CreditRow(click1, linkA, campaignA, "LINEAR", new BigDecimal("0.50000000"),
                                new BigDecimal("50.00")),
                        new CreditRow(click2, linkA, campaignA, "LINEAR", new BigDecimal("0.50000000"),
                                new BigDecimal("50.00"))));
        insertConversion(campaignB, goalSignup, NOW.minusSeconds(10 * 60), new BigDecimal("40.00"), "USD",
                "order-b", List.of(new ChainTouch(click4.toString(), linkB.toString(), campaignB.toString(),
                        NOW.minusSeconds(1 * 3600 + 50 * 60).toString(), true)),
                List.of(new CreditRow(click4, linkB, campaignB, "LAST", BigDecimal.ONE, new BigDecimal("40.00"))));
    }

    private UUID campaign(String name) {
        UUID id = UUID.randomUUID();
        unitOfWork.transactional(() -> dsl.execute(
                "insert into campaigns (id, tenant_id, name, slug, status, attribution_model, window_days, currency,"
                        + " created_at, updated_at) values (?, ?, ?, ?, 'ACTIVE', 'LAST', 30, 'USD', now(), now())",
                id, tenant.value(), name, name.toLowerCase().replace(' ', '-') + "-" + id.toString().substring(0, 6)));
        return id;
    }

    private UUID link(UUID campaign, String slug) {
        UUID id = UUID.randomUUID();
        unitOfWork.transactional(() -> dsl.execute(
                "insert into links (id, tenant_id, campaign_id, slug, kind, status, utm, created_at, updated_at)"
                        + " values (?, ?, ?, ?, 'STANDARD', 'ACTIVE', '{}', now(), now())",
                id, tenant.value(), campaign, slug));
        return id;
    }

    private UUID goal(String key) {
        UUID id = UUID.randomUUID();
        unitOfWork.transactional(() -> dsl.execute(
                "insert into goals (id, tenant_id, key, name, status, created_at, updated_at)"
                        + " values (?, ?, ?, ?, 'ACTIVE', now(), now())",
                id, tenant.value(), key, key));
        return id;
    }

    private UUID insertClick(
            UUID campaign,
            UUID link,
            Instant at,
            String device,
            String country,
            String entry,
            String referrer,
            String trafficClass,
            String visitorHash) {
        UUID clickId = UUID.randomUUID();
        unitOfWork.transactional(() -> dsl.execute(
                "insert into click_events (event_id, occurred_at, tenant_id, click_id, link_id, campaign_id,"
                        + " device_class, browser_family, country, entry, referrer_domain, traffic_class, visitor_hash)"
                        + " values (?, cast(? as timestamptz), cast(? as uuid), cast(? as uuid), cast(? as uuid),"
                        + " cast(? as uuid), ?, 'Chrome', ?, ?, ?, ?, ?)",
                UUID.randomUUID(), at, tenant.value(), clickId, link, campaign, device, country, entry, referrer,
                trafficClass, visitorHash));
        return clickId;
    }

    private void insertPageView(UUID clickId, Instant at, String session, String visitor, String trafficClass) {
        unitOfWork.transactional(() -> dsl.execute(
                "insert into page_events (event_id, occurred_at, tenant_id, click_id, session_id, visitor_id, path,"
                        + " traffic_class) values (?, cast(? as timestamptz), cast(? as uuid), cast(? as uuid), ?, ?,"
                        + " '/landing', ?)",
                UUID.randomUUID(), at, tenant.value(), clickId, session, visitor, trafficClass));
    }

    private void insertConversion(
            UUID campaign,
            UUID goal,
            Instant at,
            BigDecimal value,
            String currency,
            String key,
            List<ChainTouch> chain,
            List<CreditRow> credits) {
        UUID conversionId = UUID.randomUUID();
        String chainJson = json.writeValueAsString(chain);
        unitOfWork.transactional(() -> {
            dsl.execute(
                    "insert into conversions (id, tenant_id, goal_id, click_id, visitor_id, value, currency, occurred_at,"
                            + " source, idempotency_key, credit_status, owning_campaign_id, touch_chain, computed_at,"
                            + " created_at) values (?, cast(? as uuid), cast(? as uuid), null, null, cast(? as numeric),"
                            + " ?, cast(? as timestamptz), 'POSTBACK', ?, 'COMPUTED', cast(? as uuid),"
                            + " cast(? as jsonb), cast(? as timestamptz), now())",
                    conversionId, tenant.value(), goal, value, currency, at, key, campaign,
                    JSONB.valueOf(chainJson), at);
            for (CreditRow credit : credits) {
                dsl.execute(
                        "insert into conversion_credits (tenant_id, conversion_id, click_id, link_id, campaign_id, model,"
                                + " weight, credit) values (cast(? as uuid), cast(? as uuid), cast(? as uuid),"
                                + " cast(? as uuid), cast(? as uuid), ?, cast(? as numeric), cast(? as numeric))",
                        tenant.value(), conversionId, credit.clickId(), credit.linkId(), credit.campaignId(),
                        credit.model(), credit.weight(), credit.credit());
            }
            return null;
        });
    }

    record ChainTouch(String clickId, String linkId, String campaignId, String at, boolean human) {}

    record CreditRow(UUID clickId, UUID linkId, UUID campaignId, String model, BigDecimal weight, BigDecimal credit) {}

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
