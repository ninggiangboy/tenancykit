package dev.ngb.billing.platform.application.tenantsystem.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.billing.platform.application.tenantsystem.port.TenantSystemRepository;
import dev.ngb.billing.platform.domain.tenantsystem.TenantSystem;

/** Tenant Systems (the Tenant's integrated applications) of the current Tenant. */
public class CreateTenantSystemUseCase {

    private final UnitOfWork unitOfWork;
    private final TenantSystemRepository systems;
    private final AuditLog auditLog;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateTenantSystemUseCase(
            UnitOfWork unitOfWork, TenantSystemRepository systems, AuditLog auditLog, TenantContext tenantContext, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.systems = systems;
        this.auditLog = auditLog;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public TenantSystem create(Audit caller, String name) {
        return unitOfWork.transactional(() -> {
            TenantId tenantId = tenantContext.requireTenant();
            TenantSystem system = TenantSystem.create(new TenantSystemId(ids.newId()), tenantId, name, clock.now());
            systems.add(system);
            auditLog.record(AuditEntry.tenant(tenantId, caller.actor(), "tenant-system.created", "tenant-system/" + system.id(), caller.requestId(), clock.now()));
            return system;
        });
    }

}
