package dev.ngb.core.audit;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.Objects;

/**
 * The one way a use case writes an audit entry for the tenant it is acting in: the actor and the request id come
 * from the caller's {@link Audit} and the tenant from the tenant context, so the preamble of an entry is never
 * spelled out twice. It runs inside the caller's transaction, like the {@link AuditLog} it writes to.
 */
public final class AuditPreamble {

    private final AuditLog auditLog;
    private final TenantContext tenantContext;

    public AuditPreamble(AuditLog auditLog, TenantContext tenantContext) {
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
    }

    /** The tenant the current call acts in, for the few callers that must name it themselves. */
    public TenantId tenantId() {
        return tenantContext.requireTenant();
    }

    public void record(Audit audit, String action, String target, Instant now) {
        auditLog.record(AuditEntry.tenant(tenantContext.requireTenant(), audit.actor(), action, target, audit.requestId(), now));
    }
}
