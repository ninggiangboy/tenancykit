package dev.ngb.platform.tenancy.application.entitlement.command;

import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.tenancy.application.entitlement.exception.UnknownAppException;
import dev.ngb.platform.tenancy.application.entitlement.port.AppCatalog;
import dev.ngb.platform.tenancy.application.entitlement.port.AppEntitlementRepository;
import dev.ngb.platform.tenancy.application.entitlement.service.EntitlementRows;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantNotFoundException;
import dev.ngb.platform.tenancy.application.tenant.port.TenantRepository;
import dev.ngb.platform.tenancy.domain.entitlement.AppEntitlement;
import dev.ngb.platform.tenancy.domain.entitlement.EntitlementEvents;
import java.time.Instant;

/** Enables one app of the suite for one Tenant. Re-enabling is a no-op that publishes nothing. */
public class EnableAppUseCase {

    public record Command(TenantId tenantId, String appCode, String actor, String requestId, ExpectedVersion expected) {}

    private final UnitOfWork unitOfWork;
    private final TenantRepository tenants;
    private final AppEntitlementRepository entitlements;
    private final AppCatalog apps;
    private final EventPublisher events;
    private final AuditLog auditLog;
    private final IdGenerator ids;
    private final Clock clock;

    public EnableAppUseCase(
            UnitOfWork unitOfWork, TenantRepository tenants, AppEntitlementRepository entitlements, AppCatalog apps, EventPublisher events,
            AuditLog auditLog, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenants = tenants;
        this.entitlements = entitlements;
        this.apps = apps;
        this.events = events;
        this.auditLog = auditLog;
        this.ids = ids;
        this.clock = clock;
    }

    public AppEntitlement execute(Command command) {
        return unitOfWork.transactional(() -> {
            tenants.findById(command.tenantId()).orElseThrow(() -> new TenantNotFoundException(command.tenantId().toString()));
            if (!apps.appCodes().contains(command.appCode())) {
                throw new UnknownAppException(command.appCode());
            }
            Instant now = clock.now();
            EntitlementRows.Locked locked = EntitlementRows.lockOrInsert(
                    entitlements, AppEntitlement.enable(command.tenantId(), command.appCode(), now), command.expected());
            AppEntitlement entitlement = locked.entitlement();
            if (!locked.inserted() && !entitlement.setEnabled(true, now)) {
                return entitlement;
            }
            if (!locked.inserted()) {
                entitlements.update(entitlement);
            }
            events.publish(new EntitlementEvents.EntitlementChanged(ids.newId(), entitlement, now));
            auditLog.record(AuditEntry.platform(
                    command.actor(), "entitlement.enabled", AggregateTargets.entitlement(command.tenantId(), command.appCode()),
                    command.requestId(), now));
            return entitlement;
        });
    }
}
