package dev.ngb.attribution.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.attribution.links.adapter.campaign.persistence.JooqCampaignReadRepository;
import dev.ngb.attribution.links.adapter.campaign.persistence.JooqCampaignRepository;
import dev.ngb.attribution.links.adapter.link.persistence.JooqBulkRequestStore;
import dev.ngb.attribution.links.adapter.link.persistence.JooqLinkReadRepository;
import dev.ngb.attribution.links.adapter.link.persistence.JooqLinkRepository;
import dev.ngb.attribution.links.application.campaign.command.CreateCampaignUseCase;
import dev.ngb.attribution.links.application.campaign.command.UpdateCampaignUseCase;
import dev.ngb.attribution.links.application.campaign.query.CampaignListing;
import dev.ngb.attribution.links.application.campaign.query.CampaignQueries;
import dev.ngb.attribution.links.application.link.command.CreateBulkLinksUseCase;
import dev.ngb.attribution.links.application.link.command.CreateLinkUseCase;
import dev.ngb.attribution.links.application.link.command.UpdateLinkUseCase;
import dev.ngb.attribution.links.application.link.port.BulkRequestStore;
import dev.ngb.attribution.links.application.link.port.ClickParamProvider;
import dev.ngb.attribution.links.application.link.query.LinkListing;
import dev.ngb.attribution.links.application.link.query.LinkQueries;
import dev.ngb.attribution.links.application.link.service.SuiteHosts;
import dev.ngb.attribution.links.domain.campaign.AttributionModel;
import dev.ngb.attribution.links.domain.campaign.Campaign;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.attribution.links.domain.link.LinkKind;
import dev.ngb.attribution.support.ListQueries;
import dev.ngb.attribution.support.PostgresContainerConfig;
import dev.ngb.core.error.IdempotencyConflictException;
import dev.ngb.core.error.PreconditionFailedException;
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
import dev.ngb.core.version.ExpectedVersion;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Campaigns and links against the real migrations: a slug conflict, a stale {@code If-Match}, a bulk replay and the
 * tenant isolation the suite promises.
 *
 * <p>The tenant is bound explicitly here because the production wiring sets it inside each transaction; a test that
 * forgot would read nothing and prove nothing.
 */
class LinksIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);

    static {
        POSTGRES.start();
    }

    private final JsonMapper json = new JsonMapper();
    private final Clock clock = new SystemClock();
    private final IdGenerator ids = new UuidV7IdGenerator(clock);
    private final TenantId tenantA = new TenantId(UUID.randomUUID());
    private final TenantId tenantB = new TenantId(UUID.randomUUID());
    private final SuiteHosts hosts = new SuiteHosts(List.of("suite.example"), "https://acme.suite.example");

    private DSLContext dsl;
    private TransactionAwareDataSourceProxy proxied;
    private UnitOfWork unitOfWork;
    private TenantContext tenantContext;
    private JooqCampaignRepository campaignRepository;
    private CreateCampaignUseCase createCampaign;
    private UpdateCampaignUseCase updateCampaign;
    private CampaignQueries campaignQueries;
    private JooqLinkRepository linkRepository;
    private CreateLinkUseCase createLink;
    private UpdateLinkUseCase updateLink;
    private CreateBulkLinksUseCase bulkLinks;
    private LinkQueries linkQueries;

    @BeforeEach
    void setUp() {
        // The owner resets the schema and runs the migrations; attribution_app does not exist until they create it.
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

        // Every use case below connects as attribution_app: a superuser bypasses row-level security entirely, so a test
        // that used one would prove nothing about tenant isolation.
        PGSimpleDataSource app = new PGSimpleDataSource();
        app.setUrl(POSTGRES.getJdbcUrl());
        app.setUser(PostgresContainerConfig.APP_ROLE);
        app.setPassword(PostgresContainerConfig.APP_PASSWORD);
        proxied = new TransactionAwareDataSourceProxy(app);
        dsl = DSL.using(proxied, SQLDialect.POSTGRES);

        tenantContext = new FixedTenantContext();
        // Bind the tenant on every new transaction, exactly as the production wiring does, so row-level security applies.
        unitOfWork = new SpringUnitOfWork(
                new DataSourceTransactionManager(app),
                new TestScopeHook(tenantContext, new org.springframework.jdbc.core.JdbcTemplate(proxied)));
        campaignRepository = new JooqCampaignRepository(dsl);
        createCampaign = new CreateCampaignUseCase(unitOfWork, tenantContext, campaignRepository, ids, clock);
        updateCampaign = new UpdateCampaignUseCase(
                unitOfWork, campaignRepository,
                dev.ngb.attribution.links.application.campaign.port.RecomputeTrigger.none(), clock);
        campaignQueries = new CampaignQueries(unitOfWork, new JooqCampaignReadRepository(dsl));
        linkRepository = new JooqLinkRepository(dsl, json);
        ClickParamProvider clickParam = () -> "aid";
        createLink = new CreateLinkUseCase(
                unitOfWork, tenantContext, linkRepository, hosts, clickParam, new TestPasswordHasher(), ids, clock);
        updateLink = new UpdateLinkUseCase(
                unitOfWork, linkRepository, hosts, clickParam, new TestPasswordHasher(), ids, clock);
        BulkRequestStore requests = new JooqBulkRequestStore(dsl, json, tenantContext, clock);
        bulkLinks = new CreateBulkLinksUseCase(
                unitOfWork, tenantContext, linkRepository, requests, hosts, clickParam, ids, clock);
        linkQueries = new LinkQueries(unitOfWork, new JooqLinkReadRepository(dsl, json));
    }

    @Test
    void aSlugIsUniquePerTenant() {
        asTenant(tenantA, () -> createCampaign.execute(command("Spring", "spring-sale")));
        assertThatThrownBy(() -> asTenant(tenantA, () -> createCampaign.execute(command("Other", "spring-sale"))))
                .isInstanceOf(dev.ngb.core.error.ConflictException.class);
        // Another tenant may use the same slug.
        assertThat(asTenant(tenantB, () -> createCampaign.execute(command("Spring", "spring-sale"))).slug())
                .isEqualTo("spring-sale");
    }

    @Test
    void aStaleIfMatchIsRefusedWithTheCurrentVersion() {
        Campaign campaign = asTenant(tenantA, () -> createCampaign.execute(command("Spring", "spring-sale")));
        asTenant(tenantA, () -> updateCampaign.execute(
                campaign.id(),
                new UpdateCampaignUseCase.Change("Spring v2", null, AttributionModel.LINEAR, 30, null, null),
                ExpectedVersion.any()));
        assertThatThrownBy(() -> asTenant(tenantA, () -> updateCampaign.execute(
                        campaign.id(),
                        new UpdateCampaignUseCase.Change("Spring v3", null, AttributionModel.LINEAR, 30, null, null),
                        ExpectedVersion.fromIfMatch(ExpectedVersion.etag(campaign.version())))))
                .isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void aBulkRequestOfOneThousandRowsIsDistinctAndAReplayReturnsTheSameList() {
        Campaign campaign = asTenant(tenantA, () -> createCampaign.execute(command("Spring", "spring-sale")));
        List<CreateBulkLinksUseCase.Row> rows = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(index -> new CreateBulkLinksUseCase.Row("ref-" + index))
                .toList();
        CreateBulkLinksUseCase.Command command = new CreateBulkLinksUseCase.Command(
                campaign.id(), "https://example.com/x", Map.of("utm_source", "newsletter"), null, rows, "batch-1");

        BulkRequestStore.BulkResult first = asTenant(tenantA, () -> bulkLinks.execute(command));
        BulkRequestStore.BulkResult replay = asTenant(tenantA, () -> bulkLinks.execute(command));

        assertThat(first.links()).hasSize(1000);
        assertThat(first.links().stream().map(BulkRequestStore.PersonalLink::slug).distinct()).hasSize(1000);
        assertThat(replay.links()).isEqualTo(first.links());

        // The same key with a different body is refused rather than minting a second batch.
        CreateBulkLinksUseCase.Command changed = new CreateBulkLinksUseCase.Command(
                campaign.id(), "https://example.com/other", Map.of(), null, rows, "batch-1");
        assertThatThrownBy(() -> asTenant(tenantA, () -> bulkLinks.execute(changed)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void oneTenantCannotReadAnothersCampaignOrLink() {
        Campaign campaign = asTenant(tenantA, () -> createCampaign.execute(command("Spring", "spring-sale")));
        Link link = asTenant(tenantA, () -> createLink.execute(linkCommand(campaign.id())));

        assertThat(asTenant(tenantB, () -> campaignQueries.list(ListQueries.of(CampaignListing.SCHEMA)).data())).isEmpty();
        assertThat(asTenant(tenantB, () -> linkQueries.list(ListQueries.of(LinkListing.SCHEMA)).data())).isEmpty();
        assertThatThrownBy(() -> asTenant(tenantB, () -> campaignQueries.get(campaign.id())))
                .isInstanceOf(dev.ngb.core.error.NotFoundException.class);
        assertThatThrownBy(() -> asTenant(tenantB, () -> linkQueries.get(link.id())))
                .isInstanceOf(dev.ngb.core.error.NotFoundException.class);
    }

    @Test
    void aPasswordIsStoredAsAHashAndNeverReturnedInTheView() {
        Campaign campaign = asTenant(tenantA, () -> createCampaign.execute(command("Spring", "spring-sale")));
        CreateLinkUseCase.Command command = new CreateLinkUseCase.Command(
                campaign.id(), "spring-link", null, null, null, "s3cret",
                Map.of(), List.of(new CreateLinkUseCase.DestinationInput(
                        "https://example.com/x", 1, List.of(), List.of(), true)));
        Link link = asTenant(tenantA, () -> createLink.execute(command));

        assertThat(link.passwordHash()).isEqualTo("hash:s3cret");
        assertThat(asTenant(tenantA, () -> linkQueries.get(link.id())).hasPassword()).isTrue();
    }

    @Test
    void aLinkCannotBeCreatedWithZeroOrTwoFallbacks() {
        Campaign campaign = asTenant(tenantA, () -> createCampaign.execute(command("Spring", "spring-sale")));
        CreateLinkUseCase.Command none = new CreateLinkUseCase.Command(
                campaign.id(), null, null, null, null, null, Map.of(),
                List.of(new CreateLinkUseCase.DestinationInput("https://example.com/a", 1, List.of(), List.of(), false)));
        assertThatThrownBy(() -> asTenant(tenantA, () -> createLink.execute(none)))
                .isInstanceOf(dev.ngb.core.error.BadRequestException.class);
    }

    @Test
    void aDestinationThatNamesTheClickParameterIsRefused() {
        Campaign campaign = asTenant(tenantA, () -> createCampaign.execute(command("Spring", "spring-sale")));
        CreateLinkUseCase.Command command = new CreateLinkUseCase.Command(
                campaign.id(), null, null, null, null, null, Map.of(),
                List.of(new CreateLinkUseCase.DestinationInput(
                        "https://example.com/x?aid=stale", 1, List.of(), List.of(), true)));
        assertThatThrownBy(() -> asTenant(tenantA, () -> createLink.execute(command)))
                .isInstanceOf(dev.ngb.core.error.BadRequestException.class);
    }

    private CreateCampaignUseCase.Command command(String name, String slug) {
        return new CreateCampaignUseCase.Command(name, slug, null, AttributionModel.LAST, 30, "USD", null, null);
    }

    private CreateLinkUseCase.Command linkCommand(UUID campaignId) {
        return new CreateLinkUseCase.Command(
                campaignId, "spring-link", "ref-1", Instant.now().plusSeconds(86_400), null, null,
                Map.of("utm_source", "newsletter"),
                List.of(
                        new CreateLinkUseCase.DestinationInput("https://example.com/a", 1, List.of(), List.of(), false),
                        new CreateLinkUseCase.DestinationInput("https://example.com/b", 1, List.of(), List.of(), true)));
    }

    private <T> T asTenant(TenantId tenant, Supplier<T> work) {
        ((FixedTenantContext) tenantContext).tenant = tenant;
        return work.get();
    }

    /** Tells PostgreSQL which tenant each new transaction belongs to; without it the app role reads nothing. */
    private record TestScopeHook(TenantContext tenantContext, org.springframework.jdbc.core.JdbcTemplate jdbc)
            implements TransactionScopeHook {

        @Override
        public RequestScope currentScope() {
            return tenantContext.current();
        }

        @Override
        public void onNewTransaction(RequestScope scope) {
            // Transaction-local, so a pooled connection never carries one tenant's setting into the next transaction.
            jdbc.queryForObject(
                    "select set_config('app.tenant_id', ?, true)", String.class, scope.dataTenantId().toString());
        }
    }

    /** A deterministic stand-in for BCrypt: fast, and it records that the raw value was hashed rather than stored. */
    private static final class TestPasswordHasher implements dev.ngb.attribution.links.application.link.port.PasswordHasher {

        @Override
        public String hash(String raw) {
            return "hash:" + raw;
        }

        @Override
        public boolean matches(String raw, String hash) {
            return hash != null && hash.equals("hash:" + raw);
        }
    }

    /** Binds the current tenant for a test; the production wiring binds it inside each transaction. */
    private static final class FixedTenantContext implements TenantContext {

        private TenantId tenant;

        @Override
        public RequestScope current() {
            return RequestScope.tenant(tenant);
        }

        @Override
        public <T> T callAs(RequestScope scope, Supplier<T> work) {
            return work.get();
        }
    }
}
