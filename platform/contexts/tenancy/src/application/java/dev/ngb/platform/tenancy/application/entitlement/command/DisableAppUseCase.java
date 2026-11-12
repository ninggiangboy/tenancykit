package dev.ngb.platform.tenancy.application.entitlement.command;

import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.tenancy.application.entitlement.exception.UnknownAppException;
import dev.ngb.platform.tenancy.application.entitlement.port.AppCatalog;
import dev.ngb.platform.tenancy.application.entitlement.port.AppEntitlementRepository;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantNotFoundException;
import dev.ngb.platform.tenancy.application.tenant.port.TenantRepository;
import dev.ngb.platform.tenancy.domain.entitlement.AppEntitlement;
import dev.ngb.platform.tenancy.domain.entitlement.EntitlementEvents;
import java.time.Instant;

/** Disables one app of the suite for one Tenant. Disabling twice is a no-op that publishes nothing. */
public class DisableAppUseCase {

    public record Command(TenantId tenantId, String appCode, String actor, String requestId) {}

    private final UnitOfWork unitOfWork;
    private final TenantRepository tenants;
    private final AppEntitlementRepository entitlements;
    private final AppCatalog apps;
    private final EventPublisher events;
    private final AuditLog auditLog;
    private final IdGenerator ids;
    private final Clock clock;

    public DisableAppUseCase(
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
            AppEntitlement entitlement = entitlements.find(command.tenantId(), command.appCode())
                    .map(existing -> {
                        existing.setEnabled(false, now);
                        return existing;
                    })
                    .orElseGet(() -> AppEntitlement.disable(command.tenantId(), command.appCode(), now));
            entitlements.upsert(entitlement);
            events.publish(new EntitlementEvents.EntitlementChanged(ids.newId(), entitlement, now));
            auditLog.record(AuditEntry.platform(
                    command.actor(), "entitlement.disabled", "tenant/" + command.tenantId() + "/app/" + command.appCode(),
                    command.requestId(), now));
            return entitlement;
        });
    }
}
