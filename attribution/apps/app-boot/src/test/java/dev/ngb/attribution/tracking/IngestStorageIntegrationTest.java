package dev.ngb.attribution.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.tracking.adapter.ingest.persistence.JooqClickStore;
import dev.ngb.attribution.tracking.adapter.ingest.persistence.JooqPartitionStore;
import dev.ngb.attribution.tracking.api.ingest.ClickEvent;
import dev.ngb.attribution.tracking.application.ingest.command.IngestClicksUseCase;
import dev.ngb.attribution.tracking.application.ingest.port.PartitionStore;
import dev.ngb.attribution.support.PostgresContainerConfig;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.visitor.DeviceClass;
import dev.ngb.core.visitor.TrafficClass;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
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
 * The ingest store against the real migrations: every click stored once under its own tenant, a redelivered batch
 * writing nothing twice, one poison row isolated rather than blocking its neighbours, and the partition maintenance an
 * app role may drive but a raw {@code create table} it may not.
 */
class IngestStorageIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);

    static {
        POSTGRES.start();
    }

    private final TenantId tenantA = new TenantId(UUID.randomUUID());
    private final TenantId tenantB = new TenantId(UUID.randomUUID());

    private DSLContext appDsl;
    private DSLContext ownerDsl;
    private UnitOfWork unitOfWork;
    private FixedTenantContext tenantContext;
    private JooqClickStore store;
    private IngestClicksUseCase useCase;
    private JooqPartitionStore partitions;

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
        appDsl = DSL.using(proxied, SQLDialect.POSTGRES);

        tenantContext = new FixedTenantContext();
        unitOfWork = new SpringUnitOfWork(
                new DataSourceTransactionManager(app),
                new TestScopeHook(tenantContext, new JdbcTemplate(proxied)));
        store = new JooqClickStore(appDsl);
        useCase = new IngestClicksUseCase(unitOfWork, tenantContext, store, 2);
        partitions = new JooqPartitionStore(appDsl);
    }

    @Test
    void fiveThousandClicksAcrossTwoTenantsAreEachStoredUnderTheirOwnTenant() {
        List<ClickEvent> batch = new ArrayList<>();
        for (int index = 0; index < 5_000; index++) {
            batch.add(click(index % 2 == 0 ? tenantA : tenantB));
        }

        IngestClicksUseCase.Result result = useCase.execute(batch);

        assertThat(result.stored()).isEqualTo(5_000);
        assertThat(result.deadCount()).isZero();
        // Read each tenant through its own scope: row-level security must hide the other tenant's rows.
        tenantContext.tenant = tenantA;
        assertThat(unitOfWork.transactional(() -> appDsl.fetchCount(DSL.table("click_events")))).isEqualTo(2_500);
        tenantContext.tenant = tenantB;
        assertThat(unitOfWork.transactional(() -> appDsl.fetchCount(DSL.table("click_events")))).isEqualTo(2_500);
    }

    @Test
    void publishingTheSameBatchTwiceStoresItOnce() {
        List<ClickEvent> batch = List.of(click(tenantA), click(tenantA), click(tenantA));

        useCase.execute(batch);
        IngestClicksUseCase.Result replay = useCase.execute(batch);

        assertThat(replay.stored()).isZero();
        assertThat(replay.duplicates()).isEqualTo(3);
        tenantContext.tenant = tenantA;
        int viaApp = unitOfWork.transactional(() -> appDsl.fetchCount(DSL.table("click_events")));
        int viaOwner = ownerDsl.fetchCount(DSL.table("click_events"));
        assertThat(viaApp).as("app sees %d, owner sees %d", viaApp, viaOwner).isEqualTo(3);
    }

    @Test
    void oneInvalidRowAmongFiveHundredDoesNotBlockTheRestAndIsDead() {
        List<ClickEvent> batch = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            batch.add(click(tenantA));
        }
        // A row the store cannot insert: a visitor hash longer than the column allows, so the insert itself fails.
        ClickEvent poison = clickWithVisitorHash(tenantA, "x".repeat(200));
        batch.add(300, poison);

        IngestClicksUseCase.Result result = useCase.execute(batch);

        assertThat(result.stored()).isEqualTo(500);
        assertThat(result.deadCount()).isEqualTo(1);
        assertThat(result.dead().getFirst().eventId()).isEqualTo(poison.eventId());
        tenantContext.tenant = tenantA;
        assertThat(unitOfWork.transactional(() -> appDsl.fetchCount(DSL.table("click_events")))).isEqualTo(500);
    }

    @Test
    void partitionsExistForTheNextSevenDaysAfterTheSweep() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        int created = partitions.ensure(PartitionStore.CLICK_EVENTS, today, 8);

        // The migration already created the deployment's first week, so the sweep may create none; what matters is that
        // every day in the range has a partition.
        assertThat(created).isGreaterThanOrEqualTo(0);
        for (int offset = 0; offset < 8; offset++) {
            String name = "click_events_p" + (today.plusDays(offset)).toString().replace("-", "_");
            assertThat(ownerDsl.fetchExists(DSL.table(DSL.name("information_schema", "tables"))
                    .where(DSL.field("table_name").eq(name))))
                    .as(name + " must exist")
                    .isTrue();
        }
    }

    @Test
    void theAppRoleCanCallThePartitionFunctionButCannotCreateATable() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int created = partitions.ensure(PartitionStore.CLICK_EVENTS, today.plusDays(30), 1);
        assertThat(created).isEqualTo(1);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> appDsl.execute("create table forbidden (id int)")))
                .as("attribution_app owns nothing and may not create a table")
                .isNotNull();
    }

    @Test
    void droppingAPartitionRemovesItsRows() {
        LocalDate longAgo = LocalDate.now(ZoneOffset.UTC).minusDays(120);
        partitions.ensure(PartitionStore.CLICK_EVENTS, longAgo, 1);
        String partition = "click_events_p" + longAgo.toString().replace("-", "_");
        ownerDsl.execute("insert into " + partition
                        + " (event_id, occurred_at, tenant_id, click_id, link_id, device_class, browser_family,"
                        + " traffic_class, visitor_hash) values (?, cast(? as timestamptz), cast(? as uuid),"
                        + " cast(? as uuid), cast(? as uuid), 'DESKTOP', 'Chrome', 'HUMAN', 'hash')",
                UUID.randomUUID(), longAgo.atStartOfDay().plusHours(1).toInstant(ZoneOffset.UTC),
                tenantA.value(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(ownerDsl.fetchCount(DSL.table(DSL.name(partition)))).isEqualTo(1);

        int dropped = partitions.drop(PartitionStore.CLICK_EVENTS, longAgo.plusDays(1));

        assertThat(dropped).isEqualTo(1);
        assertThat(ownerDsl.fetchExists(DSL.table(DSL.name("information_schema", "tables"))
                .where(DSL.field("table_name").eq(partition))))
                .isFalse();
    }

    private ClickEvent click(TenantId tenant) {
        return clickWithVisitorHash(tenant, "hash-" + UUID.randomUUID());
    }

    private ClickEvent clickWithVisitorHash(TenantId tenant, String visitorHash) {
        return new ClickEvent(
                UUID.randomUUID(), tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                Instant.now(), DeviceClass.DESKTOP, "Chrome", "US", "example.com", null, TrafficClass.HUMAN,
                visitorHash);
    }

    private static final class FixedTenantContext implements TenantContext {

        private TenantId tenant;

        @Override
        public RequestScope current() {
            return RequestScope.tenant(tenant);
        }

        @Override
        public <T> T callAs(RequestScope scope, Supplier<T> work) {
            // The production context binds the scope in a ScopedValue for the duration of the call; this stand-in sets
            // the field the same way, because the ingestion use case binds each tenant group's scope itself.
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
