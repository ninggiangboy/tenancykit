package dev.ngb.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.notification.dispatch.adapter.notification.persistence.JooqTemplateOverrides;
import dev.ngb.notification.dispatch.adapter.notification.persistence.JooqTemplateSource;
import dev.ngb.notification.dispatch.application.notification.port.TemplateVariant;
import dev.ngb.notification.shared.TemplateKey;
import dev.ngb.notification.support.PostgresContainerConfig;
import java.util.Map;
import java.util.Optional;
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

/**
 * A template is the suite's content: one row serves every tenant.
 *
 * <p>This replaces the "tenant A's override does not leak into tenant B" test that no longer has a subject. The
 * property worth proving now is the opposite one — two different tenants render the same override, byte for byte —
 * because that is what "the suite owns the content" means in practice.
 */
class TemplateOverrideTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);

    static {
        POSTGRES.start();
    }

    private static final TemplateKey KEY = new TemplateKey("access-granted");

    private DSLContext dsl;
    private UnitOfWork unitOfWork;
    private ScopedTenantContext tenantContext;
    private JooqTemplateOverrides overrides;
    private JooqTemplateSource source;

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
        tenantContext = new ScopedTenantContext();
        overrides = new JooqTemplateOverrides(dsl);
        source = new JooqTemplateSource(dsl);
    }

    @Test
    void oneOverrideServesTwoDifferentTenants() {
        asPlatform(() -> overrides.save(KEY, "en", new TemplateVariant(
                "Suite subject", "<p>Shared</p>", "Shared", "Title", "Body", 1)));

        Optional<TemplateVariant> acme = asTenant(() -> source.find(KEY, "en"));
        Optional<TemplateVariant> globex = asTenant(() -> source.find(KEY, "en"));

        assertThat(acme).isPresent();
        assertThat(globex).contains(acme.orElseThrow());
        assertThat(acme.orElseThrow().subject()).isEqualTo("Suite subject");
    }

    @Test
    void aMissingOverrideIsEmptyRatherThanAnError() {
        assertThat(asTenant(() -> source.find(KEY, "vi"))).isEmpty();
    }

    @Test
    void deletingTheOverrideRemovesItForEveryTenant() {
        asPlatform(() -> overrides.save(KEY, "en", new TemplateVariant("S", null, null, null, null, 1)));

        Boolean deleted = asPlatform(() -> overrides.delete(KEY, "en"));

        assertThat(deleted).isTrue();
        assertThat(asTenant(() -> source.find(KEY, "en"))).isEmpty();
    }

    @Test
    void savingAgainBumpsTheRevisionTheLedgerRecords() {
        asPlatform(() -> overrides.save(KEY, "en", new TemplateVariant("First", null, null, null, null, 1)));
        asPlatform(() -> overrides.save(KEY, "en", new TemplateVariant("Second", null, null, null, null, 1)));

        TemplateVariant stored = asTenant(() -> source.find(KEY, "en")).orElseThrow();

        assertThat(stored.subject()).isEqualTo("Second");
        assertThat(stored.version()).isEqualTo(2);
    }

    @Test
    void theTableHasNoTenantColumnAndNoRowLevelSecurity() {
        // The structural half of the same property: content the whole suite shares cannot be filtered per tenant.
        Integer withTenantColumn = dsl.fetchOne(
                        "select count(*) from information_schema.columns "
                                + "where table_name = 'notification_templates' and column_name = 'tenant_id'")
                .get(0, Integer.class);
        Integer rlsEnabled = dsl.fetchOne(
                        "select count(*) from pg_class where relname = 'notification_templates' and relrowsecurity")
                .get(0, Integer.class);

        assertThat(withTenantColumn).isZero();
        assertThat(rlsEnabled).isZero();
    }

    private <T> T asPlatform(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), () -> unitOfWork.transactional(work::get));
    }

    private void asPlatform(Runnable work) {
        asPlatform(() -> {
            work.run();
            return null;
        });
    }

    /**
     * A tenant scope, used to prove the read path does not depend on one. The tenant id is fresh each call, so a row
     * visible from two different ids can only be shared data.
     */
    private <T> T asTenant(Supplier<T> work) {
        return tenantContext.callAs(
                RequestScope.tenant(new TenantId(UUID.randomUUID())), () -> unitOfWork.transactional(work::get));
    }
}
