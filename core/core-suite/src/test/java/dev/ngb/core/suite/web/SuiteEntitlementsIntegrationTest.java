package dev.ngb.core.suite.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import dev.ngb.core.tenancy.RequestScope;
import java.sql.SQLException;
import java.util.UUID;
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
    void setUp() throws SQLException {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("drop table if exists app_entitlements, tenants");
            statement.execute("""
                    create table tenants (
                        id uuid primary key, code text not null unique, name text not null,
                        status text not null, created_at timestamptz not null, version bigint not null default 0)""");
            statement.execute("""
                    create table app_entitlements (
                        tenant_id uuid not null references tenants (id), app_code text not null, enabled boolean not null,
                        version bigint not null default 0, primary key (tenant_id, app_code))""");
        }
        dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
        UnitOfWork unitOfWork = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource), TransactionScopeHook.none());
        tenantContext = new ScopedTenantContext();
        entitlements = new SuiteEntitlements(unitOfWork, dsl);

        tenantId = UUID.randomUUID();
        dsl.execute("insert into tenants values (?, 'acme', 'Acme', 'ACTIVE', now(), 1)", tenantId);
    }

    @Test
    void anEnabledRowLetsTheTenantUseTheApp() {
        enable(tenantId, "billing", true);

        assertThat(inTenant(() -> entitlements.enabledFor(tenantContext, "billing"))).isTrue();
    }

    @Test
    void aDisabledRowDoesNot() {
        enable(tenantId, "billing", false);

        assertThat(inTenant(() -> entitlements.enabledFor(tenantContext, "billing"))).isFalse();
    }

    @Test
    void aMissingRowDoesNot() {
        assertThat(inTenant(() -> entitlements.enabledFor(tenantContext, "billing"))).isFalse();
    }

    @Test
    void requireEnabledRefusesAnUnentitledTenant() {
        assertThatThrownBy(() -> inTenant(() -> {
                    entitlements.requireEnabled(tenantContext, "billing");
                    return null;
                }))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("this tenant has not enabled billing");
    }

    @Test
    void thePlatformScopeIsAlwaysAllowed() {
        tenantContext.runAs(RequestScope.platform(), () -> entitlements.requireEnabled(tenantContext, "billing"));
    }

    private <T> T inTenant(java.util.function.Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(new dev.ngb.core.id.TenantId(tenantId)), work);
    }

    private void enable(UUID tenant, String app, boolean enabled) {
        dsl.execute("insert into app_entitlements values (?, ?, ?, 1)", tenant, app, enabled);
    }
}
