package dev.ngb.core.suite.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import dev.ngb.core.tenancy.RequestScope;
import java.sql.SQLException;
import java.util.Optional;
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

/** Hostname resolution from the replica: the platform host, a tenant subdomain, and an unknown host. */
class SuiteHostResolverIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    private DSLContext dsl;
    private SuiteHostResolver resolver;

    @BeforeEach
    void setUp() throws SQLException {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("drop table if exists tenant_domains, tenants");
            statement.execute("""
                    create table tenants (
                        id uuid primary key, code text not null unique, name text not null,
                        status text not null, created_at timestamptz not null, version bigint not null default 0)""");
            statement.execute("""
                    create table tenant_domains (
                        id uuid primary key, tenant_id uuid references tenants (id), hostname text not null unique,
                        kind text not null, created_at timestamptz not null, version bigint not null default 0)""");
        }
        dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
        UnitOfWork unitOfWork = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource), TransactionScopeHook.none());
        resolver = new SuiteHostResolver(unitOfWork, dsl);

        UUID tenantId = UUID.randomUUID();
        dsl.execute("insert into tenants values (?, 'acme', 'Acme', 'ACTIVE', now(), 1)", tenantId);
        dsl.execute("insert into tenant_domains values (?, null, 'platform.localhost', 'PLATFORM', now(), 1)", UUID.randomUUID());
        dsl.execute(
                "insert into tenant_domains values (?, ?, 'acme.localhost', 'TENANT_SUBDOMAIN', now(), 1)",
                UUID.randomUUID(), tenantId);
    }

    @Test
    void thePlatformHostResolvesToThePlatformScope() {
        Optional<RequestScope> scope = resolver.resolve("platform.localhost");

        assertThat(scope).containsInstanceOf(RequestScope.Platform.class);
    }

    @Test
    void aTenantSubdomainResolvesToItsTenant() {
        Optional<RequestScope> scope = resolver.resolve("acme.localhost");

        assertThat(scope).isPresent();
        assertThat(scope.get()).isInstanceOf(RequestScope.Tenant.class);
        assertThat(scope.get().dataTenantId()).isNotNull();
    }

    @Test
    void anUnknownHostResolvesToNothing() {
        assertThat(resolver.resolve("unknown.localhost")).isEmpty();
    }

    @Test
    void theComparisonIsCaseInsensitive() {
        assertThat(resolver.resolve("ACME.LocalHost")).isPresent();
    }

    @Test
    void aDeactivatedTenantsHostResolvesToNothing() {
        dsl.execute("update tenants set status = 'DEACTIVATED' where code = 'acme'");

        assertThat(resolver.resolve("acme.localhost")).isEmpty();
    }
}
