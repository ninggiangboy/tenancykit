package dev.ngb.attribution.spend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.attribution.links.adapter.campaign.persistence.JooqCampaignPolicyReader;
import dev.ngb.attribution.spend.adapter.entry.persistence.JooqSpendReadRepository;
import dev.ngb.attribution.spend.adapter.entry.persistence.JooqSpendRepository;
import dev.ngb.attribution.spend.application.entry.command.BulkSetSpendUseCase;
import dev.ngb.attribution.spend.application.entry.command.DeleteManualSpendUseCase;
import dev.ngb.attribution.spend.application.entry.command.SetManualSpendUseCase;
import dev.ngb.attribution.spend.application.entry.query.SpendQueries;
import dev.ngb.attribution.spend.application.entry.query.SpendView;
import dev.ngb.attribution.spend.domain.entry.SpendEntry;
import dev.ngb.attribution.spend.domain.entry.SpendSource;
import dev.ngb.attribution.support.PostgresContainerConfig;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.runtime.SystemClock;
import dev.ngb.core.spring.runtime.UuidV7IdGenerator;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * Manual advertising cost against the real migrations: the one MANUAL row per day, a bulk set, the currency rule, a
 * delete that leaves an imported row, and tenant isolation.
 */
class SpendIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);

    static {
        POSTGRES.start();
    }

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final TenantId otherTenant = new TenantId(UUID.randomUUID());
    private final SystemClock clock = new SystemClock();

    private DSLContext dsl;
    private DSLContext ownerDsl;
    private UnitOfWork unitOfWork;
    private FixedTenantContext tenantContext;
    private JooqSpendRepository spendRepository;
    private SpendQueries spendQueries;
    private SetManualSpendUseCase set;
    private BulkSetSpendUseCase bulk;
    private DeleteManualSpendUseCase delete;
    private UUID campaign;

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
        spendRepository = new JooqSpendRepository(dsl);
        spendQueries = new SpendQueries(unitOfWork, new JooqSpendReadRepository(dsl));
        var campaigns = new JooqCampaignPolicyReader(dsl);
        var ids = new UuidV7IdGenerator(clock);
        set = new SetManualSpendUseCase(unitOfWork, tenantContext, spendRepository, campaigns, ids, clock);
        bulk = new BulkSetSpendUseCase(unitOfWork, tenantContext, spendRepository, campaigns, ids, clock);
        delete = new DeleteManualSpendUseCase(unitOfWork, spendRepository);
        campaign = campaign(tenant, "Spring A", "USD");
    }

    @Test
    void settingTheSameDayTwiceLeavesOneManualRow() {
        set.execute(command(LocalDate.parse("2026-01-15"), "100.00", "USD"));
        set.execute(command(LocalDate.parse("2026-01-15"), "150.00", "USD"));

        List<SpendView> rows = spendQueries.list(campaign, LocalDate.parse("2026-01-14"), LocalDate.parse("2026-01-16"));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.source()).isEqualTo("MANUAL");
            assertThat(row.currency()).isEqualTo("USD");
            assertThat(row.amount()).isEqualByComparingTo("150.00");
        });
    }

    @Test
    void aBulkSetWritesEveryDayInOneCall() {
        bulk.execute(new BulkSetSpendUseCase.Command(campaign, List.of(
                new BulkSetSpendUseCase.DayAmount(LocalDate.parse("2026-01-14"), new BigDecimal("10.00"), null, null,
                        null),
                new BulkSetSpendUseCase.DayAmount(LocalDate.parse("2026-01-15"), new BigDecimal("20.00"), "USD", 100L,
                        5L))));

        List<SpendView> rows = spendQueries.list(campaign, LocalDate.parse("2026-01-14"), LocalDate.parse("2026-01-15"));
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(SpendView::day)
                .containsExactly(LocalDate.parse("2026-01-14"), LocalDate.parse("2026-01-15"));
    }

    @Test
    void aCurrencyMismatchIsAConflict() {
        assertThatThrownBy(() -> set.execute(command(LocalDate.parse("2026-01-15"), "10.00", "EUR")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("currency_mismatch");
    }

    @Test
    void deletingTheManualRowLeavesAnImportedRowUntouched() {
        set.execute(command(LocalDate.parse("2026-01-15"), "100.00", "USD"));
        unitOfWork.transactional(() -> {
            spendRepository.upsert(new SpendEntry(
                    UUID.randomUUID(), tenant, campaign, LocalDate.parse("2026-01-15"), SpendSource.GOOGLE_ADS,
                    UUID.randomUUID(), new BigDecimal("25.00"), "USD", null, null, Instant.now()));
            return null;
        });

        assertThat(delete.execute(campaign, LocalDate.parse("2026-01-15"))).isEqualTo(1);

        List<SpendView> rows = spendQueries.list(campaign, LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-15"));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.source()).isEqualTo("GOOGLE_ADS");
            assertThat(row.amount()).isEqualByComparingTo("25.00");
        });
    }

    @Test
    void anotherTenantCannotSeeTheRows() {
        UUID otherCampaign = campaign(otherTenant, "Other", "USD");
        ownerDsl.execute(
                "insert into spend_entries (id, tenant_id, campaign_id, day, source, amount, currency, updated_at)"
                        + " values (?, cast(? as uuid), cast(? as uuid), cast(? as date), 'MANUAL', 99.00, 'USD', now())",
                UUID.randomUUID(), otherTenant.value(), otherCampaign, LocalDate.parse("2026-01-15"));
        set.execute(command(LocalDate.parse("2026-01-15"), "10.00", "USD"));

        assertThat(spendQueries.list(campaign, LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-15")))
                .singleElement()
                .satisfies(row -> assertThat(row.amount()).isEqualByComparingTo("10.00"));
    }

    private SetManualSpendUseCase.Command command(LocalDate day, String amount, String currency) {
        return new SetManualSpendUseCase.Command(campaign, day, new BigDecimal(amount), currency, null, null);
    }

    private UUID campaign(TenantId tenantId, String name, String currency) {
        UUID id = UUID.randomUUID();
        if (tenantId.equals(tenant)) {
            unitOfWork.transactional(() -> dsl.execute(
                    "insert into campaigns (id, tenant_id, name, slug, status, attribution_model, window_days, currency,"
                            + " created_at, updated_at) values (?, ?, ?, ?, 'ACTIVE', 'LAST', 30, ?, now(), now())",
                    id, tenant.value(), name, "spend-" + id.toString().substring(0, 8), currency));
        } else {
            ownerDsl.execute(
                    "insert into campaigns (id, tenant_id, name, slug, status, attribution_model, window_days, currency,"
                            + " created_at, updated_at) values (?, ?, ?, ?, 'ACTIVE', 'LAST', 30, ?, now(), now())",
                    id, tenantId.value(), name, "spend-" + id.toString().substring(0, 8), currency);
        }
        return id;
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
