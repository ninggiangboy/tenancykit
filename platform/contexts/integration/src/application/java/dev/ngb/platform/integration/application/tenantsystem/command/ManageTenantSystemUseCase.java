package dev.ngb.platform.integration.application.tenantsystem.command;

import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.integration.application.tenantsystem.port.TenantSystemRepository;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystem;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystemEvents;

/** Creates, renames and disables the Tenant Systems of the current Tenant. Each change publishes its state. */
public class ManageTenantSystemUseCase {

    public enum Action { RENAME, DISABLE }

    public record Command(TenantSystemId systemId, Action action, String name) {}

    private final UnitOfWork unitOfWork;
    private final TenantSystemRepository systems;
    private final EventPublisher events;
    private final AuditLog auditLog;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public ManageTenantSystemUseCase(
            UnitOfWork unitOfWork, TenantSystemRepository systems, EventPublisher events, AuditLog auditLog,
            TenantContext tenantContext, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.systems = systems;
        this.events = events;
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
            publish(system);
            auditLog.record(AuditEntry.tenant(
                    tenantId, caller.actor(), "tenant-system.created", "tenant-system/" + system.id(), caller.requestId(), clock.now()));
            return system;
        });
    }

    public TenantSystem execute(Audit caller, Command command) {
        return unitOfWork.transactional(() -> {
            TenantId tenantId = tenantContext.requireTenant();
            TenantSystem system = systems.findById(command.systemId())
                    .orElseThrow(() -> new dev.ngb.core.error.NotFoundException("tenant system not found"));
            boolean changed = switch (command.action()) {
                case RENAME -> system.rename(command.name());
                case DISABLE -> system.disable();
            };
            if (!changed) {
                return system;
            }
            systems.update(system);
            publish(system);
            auditLog.record(AuditEntry.tenant(
                    tenantId,
                    caller.actor(),
                    command.action() == Action.RENAME ? "tenant-system.renamed" : "tenant-system.disabled",
                    "tenant-system/" + system.id(),
                    caller.requestId(),
                    clock.now()));
            return system;
        });
    }

    private void publish(TenantSystem system) {
        events.publish(new TenantSystemEvents.TenantSystemChanged(ids.newId(), system, clock.now()));
    }
}
