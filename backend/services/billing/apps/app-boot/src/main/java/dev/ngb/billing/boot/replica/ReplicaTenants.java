package dev.ngb.billing.boot.replica;

import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The active tenants as the replica holds them, for background jobs. Platform is excluded: it is the pseudo-tenant
 * that owns platform-scope rows, not a business tenant a job acts for.
 */
public class ReplicaTenants {

    private final UnitOfWork unitOfWork;
    private final DSLContext dsl;

    public ReplicaTenants(UnitOfWork unitOfWork, DSLContext dsl) {
        this.unitOfWork = unitOfWork;
        this.dsl = dsl;
    }

    /** Every active tenant id, oldest first. */
    public List<UUID> activeIds() {
        return unitOfWork.transactional(() -> dsl
                .select(dev.ngb.core.replica.ReplicaTables.TENANT_ID)
                .from(dev.ngb.core.replica.ReplicaTables.TENANTS)
                .where(dev.ngb.core.replica.ReplicaTables.TENANT_STATUS.eq("ACTIVE"))
                .and(dev.ngb.core.replica.ReplicaTables.TENANT_CODE.ne(dev.ngb.core.replica.ReplicaTables.PLATFORM_TENANT_CODE))
                .orderBy(dev.ngb.core.replica.ReplicaTables.TENANT_ID)
                .fetch(dev.ngb.core.replica.ReplicaTables.TENANT_ID));
    }
}
