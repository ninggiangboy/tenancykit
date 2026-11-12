package dev.ngb.platform.tenancy.application.tenant.command;

import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantNotFoundException;
import dev.ngb.platform.tenancy.application.tenant.port.TenantRepository;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import dev.ngb.platform.tenancy.domain.tenant.TenancyEvents;
import java.time.Instant;

/** Deactivates a Tenant; deactivating twice is a no-op that publishes nothing. */
public class DeactivateTenantUseCase {

    public record Command(TenantId tenantId, String actor, String requestId) {}

    private final UnitOfWork unitOfWork;
    private final TenantRepository tenants;
    private final EventPublisher events;
    private final AuditLog auditLog;
    private final IdGenerator ids;
    private final Clock clock;

    public DeactivateTenantUseCase(
            UnitOfWork unitOfWork, TenantRepository tenants, EventPublisher events, AuditLog auditLog, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenants = tenants;
        this.events = events;
        this.auditLog = auditLog;
        this.ids = ids;
        this.clock = clock;
    }

    public Tenant execute(Command command) {
        return unitOfWork.transactional(() -> {
            Tenant tenant = tenants.findById(command.tenantId())
                    .orElseThrow(() -> new TenantNotFoundException(command.tenantId().toString()));
            if (!tenant.deactivate()) {
                return tenant;
            }
            Instant now = clock.now();
            tenants.update(tenant);
            events.publish(new TenancyEvents.TenantChanged(ids.newId(), tenant, now));
            auditLog.record(AuditEntry.platform(command.actor(), "tenant.deactivated", "tenant/" + tenant.id(), command.requestId(), now));
            return tenant;
        });
    }
}
