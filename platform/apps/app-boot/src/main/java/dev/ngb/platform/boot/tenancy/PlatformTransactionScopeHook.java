package dev.ngb.platform.boot.tenancy;

import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tells PostgreSQL which tenant each new transaction belongs to. The first statement of every new transaction sets
 * {@code app.tenant_id} transaction-locally (the reserved platform tenant for Platform scope), so row-level security
 * applies to everything that follows and nothing survives the commit on a pooled connection.
 */
public class PlatformTransactionScopeHook implements TransactionScopeHook {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    public PlatformTransactionScopeHook(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    @Override
    public Object currentScope() {
        return tenantContext.current();
    }

    @Override
    public void onNewTransaction(Object scope) {
        jdbc.queryForObject(
                "select set_config('app.tenant_id', ?, true)", String.class, ((RequestScope) scope).dataTenantId().toString());
    }
}
