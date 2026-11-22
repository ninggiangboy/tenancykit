package dev.ngb.attribution.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.attribution.attribution.adapter.conversion.persistence.JooqConversionJobs;
import dev.ngb.attribution.attribution.adapter.conversion.persistence.JooqConversionReadRepository;
import dev.ngb.attribution.attribution.adapter.conversion.persistence.JooqConversionRepository;
import dev.ngb.attribution.attribution.adapter.goal.persistence.JooqGoalReadRepository;
import dev.ngb.attribution.attribution.adapter.goal.persistence.JooqGoalRepository;
import dev.ngb.attribution.attribution.application.conversion.command.RecordConversionUseCase;
import dev.ngb.attribution.attribution.application.conversion.command.RecomputeCampaignUseCase;
import dev.ngb.attribution.attribution.application.conversion.command.SettleConversionsUseCase;
import dev.ngb.attribution.attribution.application.conversion.service.ConversionAttributor;
import dev.ngb.attribution.attribution.application.goal.command.CreateGoalUseCase;
import dev.ngb.attribution.attribution.domain.conversion.Conversion;
import dev.ngb.attribution.attribution.domain.conversion.CreditStatus;
import dev.ngb.attribution.attribution.domain.goal.Goal;
import dev.ngb.attribution.links.adapter.campaign.persistence.JooqCampaignPolicyReader;
import dev.ngb.attribution.support.PostgresContainerConfig;
import dev.ngb.attribution.tracking.adapter.collect.persistence.JooqTouchpointReader;
import dev.ngb.core.error.IdempotencyConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.runtime.SystemClock;
import dev.ngb.core.spring.runtime.UuidV7IdGenerator;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Conversions and the engine against the real migrations: the postback's idempotency, a forged token stored
 * unattributed, the vectors' credits end to end, a recompute after the raw click days are gone, and erasure.
 */
class AttributionIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);
    private static final Map<String, String> CLICK_KEYS = Map.of("k1", "test-click-secret");

    static {
        POSTGRES.start();
    }

    private final JsonMapper json = new JsonMapper();
    private final Clock clock = new SystemClock();
    private final IdGenerator ids = new UuidV7IdGenerator(clock);
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    private DSLContext dsl;
    private UnitOfWork unitOfWork;
    private FixedTenantContext tenantContext;
    private JooqGoalRepository goalRepository;
    private CreateGoalUseCase createGoal;
    private JooqConversionRepository conversionRepository;
    private RecordConversionUseCase record;
    private SettleConversionsUseCase settle;
    private RecomputeCampaignUseCase recompute;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource owner = new PGSimpleDataSource();
        owner.setUrl(POSTGRES.getJdbcUrl());
        owner.setUser(POSTGRES.getUsername());
        owner.setPassword(POSTGRES.getPassword());
        DSL.using(owner, SQLDialect.POSTGRES).execute("drop schema if exists public cascade");
        DSL.using(owner, SQLDialect.POSTGRES).execute("create schema public");
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
        goalRepository = new JooqGoalRepository(dsl);
        createGoal = new CreateGoalUseCase(unitOfWork, tenantContext, goalRepository, ids, clock);
        conversionRepository = new JooqConversionRepository(dsl, json);
        record = new RecordConversionUseCase(
                unitOfWork, tenantContext, conversionRepository, goalRepository, CLICK_KEYS, ids, clock);
        ConversionAttributor attributor = new ConversionAttributor(
                clock, new JooqTouchpointReader(dsl), new JooqCampaignPolicyReader(dsl));
        settle = new SettleConversionsUseCase(unitOfWork, tenantContext, conversionRepository, attributor);
        recompute = new RecomputeCampaignUseCase(
                unitOfWork,
                conversionRepository,
                new JooqConversionJobs(dsl, conversionRepository),
                attributor);
    }

    @Test
    void theSamePostbackKeyTwiceYieldsOneConversionAndADifferentBodyIsRefused() {
        createGoal.execute(new CreateGoalUseCase.Command("purchase", "Purchase", new BigDecimal("10.00"), "USD"));
        RecordConversionUseCase.Command command = new RecordConversionUseCase.Command(
                "purchase", null, "visitor-1", new BigDecimal("10.00"), "USD", Instant.now(), "order-1");

        RecordConversionUseCase.Result first = record.execute(command);
        RecordConversionUseCase.Result replay = record.execute(command);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.conversion().id()).isEqualTo(first.conversion().id());
        assertThat(count("conversions")).isEqualTo(1);

        assertThatThrownBy(() -> record.execute(new RecordConversionUseCase.Command(
                        "purchase", null, "visitor-1", new BigDecimal("99.00"), "USD", Instant.now(), "order-1")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void aForgedClickTokenIsStoredUnattributedRatherThanRejected() {
        createGoal.execute(new CreateGoalUseCase.Command("signup", "Signup", new BigDecimal("5.00"), "USD"));

        RecordConversionUseCase.Result result = record.execute(new RecordConversionUseCase.Command(
                "signup", "bm90LWEtdG9rZW4~azE~bm90LWEtc2ln", null, new BigDecimal("5.00"), "USD", Instant.now(),
                "order-2"));

        assertThat(result.created()).isTrue();
        assertThat(result.conversion().clickId()).isNull();
        assertThat(result.conversion().creditStatus()).isEqualTo(CreditStatus.PENDING);
    }

    @Test
    void aSettledConversionCarriesTheVectorsCredits() {
        UUID campaign = campaign("LINEAR", 30);
        Goal goal = createGoal.execute(
                new CreateGoalUseCase.Command("purchase", "Purchase", null, "USD"));
        // A chain of three human clicks: both bound to the visitor, and the conversion's own click.
        Instant base = clock.now().minus(1, ChronoUnit.HOURS);
        // Three distinct clicks: two bound to the visitor and the conversion's own, so the chain has three touches and
        // a linear split is 33.33, 33.33, 33.34 — the vector's rounding rule end to end.
        UUID first = insertClick(campaign, base.minus(3, ChronoUnit.DAYS));
        UUID second = insertClick(campaign, base.minus(2, ChronoUnit.DAYS));
        UUID own = insertClick(campaign, base.minus(1, ChronoUnit.DAYS));
        unitOfWork.transactional(() -> {
            bind("visitor-1", first, base);
            bind("visitor-1", second, base);
            return null;
        });
        RecordConversionUseCase.Result recorded = record.execute(new RecordConversionUseCase.Command(
                "purchase", token(own), "visitor-1", new BigDecimal("100.00"), "USD", base, "order-3"));

        settle.execute(List.of(load(recorded.conversion())));

        Conversion settled = load(recorded.conversion());
        assertThat(settled.creditStatus()).isEqualTo(CreditStatus.COMPUTED);
        // Compared as decimals, not as text: the stored column's scale is the currency's, and the engine's value is the
        // amount, so 33.3300 and 33.33 are the same credit.
        List<BigDecimal> credits = unitOfWork.transactional(() -> conversionRepository.credits(settled.id())).stream()
                .map(credit -> credit.credit().setScale(2, java.math.RoundingMode.UNNECESSARY))
                .toList();
        assertThat(credits).containsExactlyInAnyOrder(
                new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.34"));
        BigDecimal sum = credits.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).as("the credits sum exactly to the value").isEqualByComparingTo("100.00");
        assertThat(goal.id()).isEqualTo(settled.goalId());
    }

    @Test
    void aRecomputeFromLastToLinearAfterTheRawClickDaysAreGoneReproducesTheCredits() {
        UUID campaign = campaign("LAST", 30);
        createGoal.execute(new CreateGoalUseCase.Command("purchase", "Purchase", null, "USD"));
        Instant base = clock.now().minus(1, ChronoUnit.HOURS);
        UUID first = insertClick(campaign, base.minus(3, ChronoUnit.DAYS));
        UUID second = insertClick(campaign, base.minus(1, ChronoUnit.DAYS));
        unitOfWork.transactional(() -> {
            bind("visitor-1", first, base);
            bind("visitor-1", second, base);
            return null;
        });
        RecordConversionUseCase.Result recorded = record.execute(new RecordConversionUseCase.Command(
                "purchase", token(second), "visitor-1", new BigDecimal("90.00"), "USD", base, "order-4"));
        settle.execute(List.of(load(recorded.conversion())));
        assertThat(unitOfWork.transactional(() -> conversionRepository.credits(recorded.conversion().id()))).hasSize(1);

        // The raw clicks are dropped, the way retention would; the snapshot on the conversion is all that remains.
        unitOfWork.transactional(() -> {
            dsl.execute("delete from click_events");
            dsl.execute("update campaigns set attribution_model = 'LINEAR' where id = ?", campaign);
            return null;
        });

        RecomputeCampaignUseCase.Result result = recompute.execute(campaign, null);

        assertThat(result.recomputed()).isEqualTo(1);
        assertThat(unitOfWork.transactional(() -> conversionRepository.credits(recorded.conversion().id())).stream()
                        .map(credit -> credit.credit().setScale(2, java.math.RoundingMode.UNNECESSARY))
                        .toList())
                .containsExactlyInAnyOrder(new BigDecimal("45.00"), new BigDecimal("45.00"));
    }

    @Test
    void aConversionWithNoHumanClickIsUnattributed() {
        UUID campaign = campaign("LAST", 30);
        createGoal.execute(new CreateGoalUseCase.Command("purchase", "Purchase", null, "USD"));
        RecordConversionUseCase.Result recorded = record.execute(new RecordConversionUseCase.Command(
                "purchase", null, "visitor-with-no-clicks", new BigDecimal("12.00"), "USD", clock.now(), "order-5"));

        settle.execute(List.of(load(recorded.conversion())));

        Conversion settled = load(recorded.conversion());
        assertThat(settled.creditStatus()).isEqualTo(CreditStatus.UNATTRIBUTED);
        assertThat(campaign).isNotNull();
    }

    private Conversion load(Conversion conversion) {
        return unitOfWork.transactional(
                () -> conversionRepository.findById(conversion.id()).orElseThrow());
    }

    private int count(String table) {
        // Inside a tenant transaction, because row-level security binds through the transaction's setting.
        return unitOfWork.transactional(() -> dsl.fetchCount(DSL.table(DSL.name(table))));
    }

    private UUID campaign(String model, int windowDays) {
        UUID id = UUID.randomUUID();
        unitOfWork.transactional(() -> dsl.execute(
                "insert into campaigns (id, tenant_id, name, slug, status, attribution_model, window_days, currency,"
                        + " version, created_at, updated_at) values (?, ?, 'Spring', ?, 'ACTIVE', ?, ?, 'USD', 1, now(), now())",
                id, tenant.value(), "spring-" + id.toString().substring(0, 8), model, windowDays));
        return id;
    }

    private UUID insertClick(UUID campaign, Instant at) {
        UUID clickId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        unitOfWork.transactional(() -> dsl.execute(
                "insert into click_events (event_id, occurred_at, tenant_id, click_id, link_id, campaign_id,"
                        + " device_class, browser_family, traffic_class, visitor_hash)"
                        + " values (?, cast(? as timestamptz), cast(? as uuid), cast(? as uuid), cast(? as uuid),"
                        + " cast(? as uuid), 'DESKTOP', 'Chrome', 'HUMAN', 'hash')",
                UUID.randomUUID(), at, tenant.value(), clickId, linkId, campaign));
        return clickId;
    }

    private void bind(String visitorId, UUID clickId, Instant at) {
        dsl.execute(
                "insert into visitor_clicks (tenant_id, visitor_id, click_id, bound_at)"
                        + " values (cast(? as uuid), ?, cast(? as uuid), cast(? as timestamptz))",
                tenant.value(), visitorId, clickId, at);
    }

    private String token(UUID clickId) {
        return dev.ngb.attribution.shared.redirect.ClickToken.sign(clickId, "k1", "test-click-secret");
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
