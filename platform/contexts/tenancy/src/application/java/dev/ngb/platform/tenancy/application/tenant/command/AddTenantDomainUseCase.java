package dev.ngb.platform.tenancy.application.tenant.command;

import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.tenancy.application.tenant.exception.HostnameAlreadyMappedException;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantNotFoundException;
import dev.ngb.platform.tenancy.application.tenant.port.ConsoleHostPort;
import dev.ngb.platform.tenancy.application.tenant.port.TenantDomainRepository;
import dev.ngb.platform.tenancy.application.tenant.port.TenantRepository;
import dev.ngb.platform.tenancy.domain.tenant.DomainKind;
import dev.ngb.platform.tenancy.domain.tenant.Hostname;
import dev.ngb.platform.tenancy.domain.tenant.TenantDomain;
import dev.ngb.platform.tenancy.domain.tenant.TenancyEvents;
import java.time.Instant;

/**
 * Maps an additional hostname to a Tenant. Runs at Platform scope.
 *
 * <p>A Tenant's people sign in on the console of their own host, so the host also becomes a redirect target Keycloak
 * trusts. That call happens after the domain commits and never fails the request: the reconciler repairs whatever it
 * could not reach.
 */
public class AddTenantDomainUseCase {

    private static final System.Logger LOG = System.getLogger(AddTenantDomainUseCase.class.getName());

    public record Command(TenantId tenantId, String hostname, String actor, String requestId) {}

    private final UnitOfWork unitOfWork;
    private final TenantRepository tenants;
    private final TenantDomainRepository domains;
    private final ConsoleHostPort consoleHosts;
    private final EventPublisher events;
    private final AuditLog auditLog;
    private final IdGenerator ids;
    private final Clock clock;

    public AddTenantDomainUseCase(
            UnitOfWork unitOfWork, TenantRepository tenants, TenantDomainRepository domains, ConsoleHostPort consoleHosts,
            EventPublisher events, AuditLog auditLog, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenants = tenants;
        this.domains = domains;
        this.consoleHosts = consoleHosts;
        this.events = events;
        this.auditLog = auditLog;
        this.ids = ids;
        this.clock = clock;
    }

    public TenantDomain execute(Command command) {
        TenantDomain added = unitOfWork.transactional(() -> {
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
                    command.actor(), "tenant.domain-added", AggregateTargets.with(AggregateTargets.tenant(command.tenantId()), "domain", hostname), command.requestId(), now));
            return domain;
        });
        registerConsoleHost(added);
        return added;
    }

    private void registerConsoleHost(TenantDomain domain) {
        try {
            consoleHosts.register(domain.hostname().value());
        } catch (RuntimeException failure) {
            // The domain is committed; the reconciler makes the Keycloak client equal the hosts that exist, so a
            // Keycloak that is down now must not fail a request the Platform has already accepted.
            LOG.log(System.Logger.Level.WARNING,
                    "the console redirect sync will repair host " + domain.hostname().value(), failure);
        }
    }
}
