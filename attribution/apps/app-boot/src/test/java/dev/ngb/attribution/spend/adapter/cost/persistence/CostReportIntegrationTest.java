package dev.ngb.attribution.spend.adapter.cost.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.attribution.adapter.conversion.persistence.JooqConversionRollupSource;
import dev.ngb.attribution.insight.adapter.report.persistence.JooqReportReadRepository;
import dev.ngb.attribution.insight.adapter.rollup.persistence.JooqRollupStateReader;
import dev.ngb.attribution.insight.application.report.query.CostReportView;
import dev.ngb.attribution.insight.application.report.query.ReportQueries;
import dev.ngb.attribution.insight.application.report.query.ReportRange;
import dev.ngb.attribution.links.adapter.campaign.persistence.JooqCampaignPolicyReader;
import dev.ngb.attribution.links.adapter.link.persistence.JooqLinkMetadataReader;
import dev.ngb.attribution.support.PostgresContainerConfig;
import dev.ngb.attribution.tracking.adapter.traffic.persistence.JooqTrafficSource;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
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
 * The cost report against the real migrations: cost beside human clicks and credited conversions, the four ratios, a
 * zero denominator as null, a second currency excluded from the value, the per-source breakdown and tenant isolation.
 */
class CostReportIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);
    private static final Instant FROM = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-16T00:00:00Z");

    static {
        POSTGRES.start();
    }

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final TenantId otherTenant = new TenantId(UUID.randomUUID());

    private DSLContext dsl;
    private DSLContext ownerDsl;
    private UnitOfWork unitOfWork;
    private FixedTenantContext tenantContext;
    private ReportQueries reports;
    private UUID campaignA;
    private UUID campaignB;
    private UUID campaignC;

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
        reports = new ReportQueries(
                unitOfWork,
                new JooqReportReadRepository(dsl, new JooqTrafficSource(dsl), new JooqConversionRollupSource(dsl)),
                new JooqRollupStateReader(dsl),
                new JooqCostReader(dsl),
                new JooqCampaignPolicyReader(dsl),
                new JooqLinkMetadataReader(dsl));

        campaignA = campaign("Campaign A", "USD");
        campaignB = campaign("Campaign B", "USD");
        campaignC = campaign("Campaign C", "USD");

        traffic(campaignA, 2);
        traffic(campaignC, 1);
        conversion(campaignA, "USD", "1", "100.00");
        conversion(campaignA, "EUR", "1", "10.00");
        spend(campaignA, "MANUAL", "100.00");
        spend(campaignA, "GOOGLE_ADS", "25.00");
        spend(campaignB, "MANUAL", "50.00");
    }

    @Test
    void theRatiosAndTheSourceBreakdownAreExact() {
        CostReportView view = reports.costs(new ReportRange(FROM, TO), campaignA, "campaign");

        assertThat(view.rows()).singleElement().satisfies(row -> {
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
        });
    }

    @Test
    void zeroClicksGiveNullCpcAndCpaAndZeroCostGivesNullRoasAndRoi() {
        CostReportView noClicks = reports.costs(new ReportRange(FROM, TO), campaignB, "campaign");
        assertThat(noClicks.rows()).singleElement().satisfies(row -> {
            assertThat(row.cost()).isEqualByComparingTo("50.00");
            assertThat(row.humanClicks()).isZero();
            assertThat(row.cpc()).isNull();
            assertThat(row.cpa()).isNull();
            assertThat(row.roas()).isEqualByComparingTo("0.0000");
            assertThat(row.roi()).isEqualByComparingTo("-1.0000");
        });

        CostReportView noCost = reports.costs(new ReportRange(FROM, TO), campaignC, "campaign");
        assertThat(noCost.rows()).singleElement().satisfies(row -> {
            assertThat(row.cost()).isEqualByComparingTo("0");
            assertThat(row.humanClicks()).isEqualTo(1);
            assertThat(row.roas()).isNull();
            assertThat(row.roi()).isNull();
            assertThat(row.cpa()).isNull();
        });
    }

    @Test
    void anotherTenantsCostAndClicksAreInvisible() {
        UUID otherCampaign = UUID.randomUUID();
        ownerDsl.execute(
                "insert into campaigns (id, tenant_id, name, slug, status, attribution_model, window_days, currency,"
                        + " created_at, updated_at) values (?, ?, 'Other', ?, 'ACTIVE', 'LAST', 30, 'USD', now(), now())",
                otherCampaign, otherTenant.value(), "other-" + otherCampaign.toString().substring(0, 8));
        ownerDsl.execute(
                "insert into spend_entries (id, tenant_id, campaign_id, day, source, amount, currency, updated_at)"
                        + " values (?, cast(? as uuid), cast(? as uuid), cast(? as date), 'MANUAL', 999.00, 'USD', now())",
                UUID.randomUUID(), otherTenant.value(), otherCampaign, java.time.LocalDate.parse("2026-01-15"));
        ownerDsl.execute(
                "insert into rollup_traffic_hourly (tenant_id, bucket, device_class, traffic_class, clicks)"
                        + " values (cast(? as uuid), cast(? as timestamptz), 'DESKTOP', 'HUMAN', 99)",
                otherTenant.value(), FROM.plusSeconds(3600));

        CostReportView view = reports.costs(new ReportRange(FROM, TO), null, "campaign");
        assertThat(view.rows()).extracting(CostReportView.Row::campaignId)
                .doesNotContain(otherCampaign)
                .contains(campaignA, campaignB, campaignC);
    }

    private UUID campaign(String name, String currency) {
        UUID id = UUID.randomUUID();
        unitOfWork.transactional(() -> dsl.execute(
                "insert into campaigns (id, tenant_id, name, slug, status, attribution_model, window_days, currency,"
                        + " created_at, updated_at) values (?, ?, ?, ?, 'ACTIVE', 'LAST', 30, ?, now(), now())",
                id, tenant.value(), name, "cost-" + id.toString().substring(0, 8), currency));
        return id;
    }

    private void traffic(UUID campaign, long clicks) {
        unitOfWork.transactional(() -> dsl.execute(
                "insert into rollup_traffic_hourly (tenant_id, bucket, campaign_id, device_class, traffic_class, clicks)"
                        + " values (cast(? as uuid), cast(? as timestamptz), cast(? as uuid), 'DESKTOP', 'HUMAN', ?)",
                tenant.value(), FROM.plusSeconds(3600), campaign, clicks));
    }

    private void conversion(UUID campaign, String currency, String conversions, String credited) {
        unitOfWork.transactional(() -> dsl.execute(
                "insert into rollup_conversions_daily (tenant_id, day, campaign_id, currency, conversions,"
                        + " credited_value, multi_touch_conversions) values (cast(? as uuid), cast(? as date),"
                        + " cast(? as uuid), ?, cast(? as numeric), cast(? as numeric), 0)",
                tenant.value(), "2026-01-15", campaign, currency, conversions, credited));
    }

    private void spend(UUID campaign, String source, String amount) {
        unitOfWork.transactional(() -> dsl.execute(
                "insert into spend_entries (id, tenant_id, campaign_id, day, source, amount, currency, updated_at)"
                        + " values (?, cast(? as uuid), cast(? as uuid), cast(? as date), ?, cast(? as numeric), 'USD',"
                        + " now())",
                UUID.randomUUID(), tenant.value(), campaign, "2026-01-15", source, amount));
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
