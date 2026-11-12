package dev.ngb.platform.tenancy.application.tenant.command;

import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.tenancy.application.tenant.exception.HostnameAlreadyMappedException;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantNotFoundException;
import dev.ngb.platform.tenancy.application.tenant.port.TenantDomainRepository;
import dev.ngb.platform.tenancy.application.tenant.port.TenantRepository;
import dev.ngb.platform.tenancy.domain.tenant.DomainKind;
import dev.ngb.platform.tenancy.domain.tenant.Hostname;
import dev.ngb.platform.tenancy.domain.tenant.TenantDomain;
import dev.ngb.platform.tenancy.domain.tenant.TenancyEvents;
import java.time.Instant;

/** Maps an additional hostname to a Tenant. Runs at Platform scope. */
public class AddTenantDomainUseCase {

    public record Command(TenantId tenantId, String hostname, String actor, String requestId) {}

    private final UnitOfWork unitOfWork;
    private final TenantRepository tenants;
    private final TenantDomainRepository domains;
    private final EventPublisher events;
    private final AuditLog auditLog;
    private final IdGenerator ids;
    private final Clock clock;

    public AddTenantDomainUseCase(
            UnitOfWork unitOfWork, TenantRepository tenants, TenantDomainRepository domains, EventPublisher events, AuditLog auditLog,
            IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenants = tenants;
        this.domains = domains;
        this.events = events;
        this.auditLog = auditLog;
        this.ids = ids;
        this.clock = clock;
    }

    public TenantDomain execute(Command command) {
        return unitOfWork.transactional(() -> {
            tenants.findById(command.tenantId())
                    .orElseThrow(() -> new TenantNotFoundException(command.tenantId().toString()));
            Hostname hostname = Hostname.fromHostHeader(command.hostname());
            if (domains.findByHostname(hostname).isPresent()) {
                throw new HostnameAlreadyMappedException(hostname.value());
            }
            Instant now = clock.now();
            TenantDomain domain = TenantDomain.forTenant(ids.newId(), command.tenantId(), hostname, DomainKind.TENANT_SUBDOMAIN, now);
            domains.add(domain);
            events.publish(new TenancyEvents.TenantDomainChanged(ids.newId(), domain, now));
            auditLog.record(AuditEntry.platform(
                    command.actor(), "tenant.domain-added", "tenant/" + command.tenantId() + "/domain/" + hostname, command.requestId(), now));
            return domain;
        });
    }
}
