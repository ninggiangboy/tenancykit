package dev.ngb.workflow.runtime.adapter.runtime.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TENANT_QUOTA;

import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.runtime.application.runtime.port.QuotaRepository;
import java.util.Optional;
import org.jooq.DSLContext;

/** A Tenant's quota row, if an operator set one. The defaults live in configuration, not in this table. */
public class JooqQuotaRepository implements QuotaRepository {

    private final DSLContext dsl;

    public JooqQuotaRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Limits> forTenant(TenantId tenantId) {
        return dsl.select(WORKFLOW_TENANT_QUOTA.CONCURRENT_INSTANCES, WORKFLOW_TENANT_QUOTA.ACTIVE_DEFINITIONS)
                .from(WORKFLOW_TENANT_QUOTA)
                .where(WORKFLOW_TENANT_QUOTA.TENANT_ID.eq(tenantId.value()))
                .fetchOptional(row -> new Limits(row.value1(), row.value2()));
    }
}
