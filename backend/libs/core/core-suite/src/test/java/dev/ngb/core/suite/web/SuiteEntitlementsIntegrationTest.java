package dev.ngb.core.suite.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.replica.ReplicaSchemaAssertions;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import dev.ngb.core.tenancy.RequestScope;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Entitlement reads: an enabled row lets the tenant use the app; a missing or disabled row does not. */
class SuiteEntitlementsIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    private DSLContext dsl;
    private ScopedTenantContext tenantContext;
    private SuiteEntitlements entitlements;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
        dsl.execute("drop table if exists user_grants, app_entitlements, tenant_systems, tenant_domains, tenants");
        ReplicaSchemaAssertions.applyReferenceDdl(dataSource);
        UnitOfWork unitOfWork = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource), TransactionScopeHook.none());
        tenantContext = new ScopedTenantContext();
        entitlements = new SuiteEntitlements(unitOfWork, dsl, tenantContext);

        tenantId = UUID.randomUUID();
        dsl.execute("insert into tenants values (?, 'acme', 'Acme', 'ACTIVE', now(), 1)", tenantId);
    }

    @Test
    void anEnabledRowLetsTheTenantUseTheApp() {
        enable(tenantId, "billing", true);

        assertThat(inTenant(() -> entitlements.enabledFor("billing"))).isTrue();
    }

    @Test
    void aDisabledRowDoesNot() {
        enable(tenantId, "billing", false);

        assertThat(inTenant(() -> entitlements.enabledFor("billing"))).isFalse();
    }

    @Test
    void aMissingRowDoesNot() {
        assertThat(inTenant(() -> entitlements.enabledFor("billing"))).isFalse();
    }

    @Test
    void anEnabledRowForOneAppDoesNotEnableAnother() {
        enable(tenantId, "billing", true);

        assertThat(inTenant(() -> entitlements.enabledFor("platform"))).isFalse();
    }

    @Test
    void thePlatformScopeIsAlwaysEnabled() {
        assertThat(tenantContext.callAs(RequestScope.platform(), () -> entitlements.enabledFor("billing"))).isTrue();
    }

    private <T> T inTenant(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(new TenantId(tenantId)), work);
    }

    private void enable(UUID tenant, String app, boolean enabled) {
        dsl.execute("insert into app_entitlements values (?, ?, ?, 1)", tenant, app, enabled);
    }
}
