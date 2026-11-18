package dev.ngb.platform.tenancy.application.tenant.command;

import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantCodeAlreadyUsedException;
import dev.ngb.platform.tenancy.application.tenant.port.ConsoleHostPort;
import dev.ngb.platform.tenancy.application.tenant.port.TenantDomainRepository;
import dev.ngb.platform.tenancy.application.tenant.port.TenantRepository;
import dev.ngb.platform.tenancy.domain.tenant.DomainKind;
import dev.ngb.platform.tenancy.domain.tenant.Hostname;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import dev.ngb.platform.tenancy.domain.tenant.TenantCode;
import dev.ngb.platform.tenancy.domain.tenant.TenantDomain;
import dev.ngb.platform.tenancy.domain.tenant.TenancyEvents;
import java.time.Instant;

/**
 * Creates a Tenant and its default subdomain in one transaction. Runs at Platform scope.
 *
 * <p>The subdomain is the address its people sign in on, so it also becomes a redirect target Keycloak trusts. That
 * call happens after the commit and never fails the request: the reconciler repairs whatever it could not reach.
 */
public class CreateTenantUseCase {

    private static final System.Logger LOG = System.getLogger(CreateTenantUseCase.class.getName());

    public record Command(String code, String name, String actor, String requestId) {}

    private record Provisioned(Tenant tenant, TenantDomain domain) {}

    private final UnitOfWork unitOfWork;
    private final TenantRepository tenants;
    private final TenantDomainRepository domains;
    private final ConsoleHostPort consoleHosts;
    private final EventPublisher events;
    private final AuditLog auditLog;
    private final IdGenerator ids;
    private final Clock clock;
    private final String baseDomain;

    public CreateTenantUseCase(
            UnitOfWork unitOfWork, TenantRepository tenants, TenantDomainRepository domains, ConsoleHostPort consoleHosts,
            EventPublisher events, AuditLog auditLog, IdGenerator ids, Clock clock, String baseDomain) {
        this.unitOfWork = unitOfWork;
        this.tenants = tenants;
        this.domains = domains;
        this.consoleHosts = consoleHosts;
        this.events = events;
        this.auditLog = auditLog;
        this.ids = ids;
        this.clock = clock;
        this.baseDomain = baseDomain;
    }

    public Tenant execute(Command command) {
        Provisioned provisioned = unitOfWork.transactional(() -> {
            TenantCode code = TenantCode.forNewTenant(command.code());
            if (tenants.findByCode(code).isPresent()) {
                throw new TenantCodeAlreadyUsedException(code.value());
            }
            Instant now = clock.now();
            Tenant tenant = Tenant.create(new TenantId(ids.newId()), code, command.name(), now);
            tenants.add(tenant);
            TenantDomain domain = TenantDomain.forTenant(
                    ids.newId(), tenant.id(), new Hostname(code.value() + "." + baseDomain), DomainKind.TENANT_SUBDOMAIN, now);
            domains.add(domain);
            events.publish(new TenancyEvents.TenantChanged(ids.newId(), tenant, now));
            events.publish(new TenancyEvents.TenantDomainChanged(ids.newId(), domain, now));
            auditLog.record(AuditEntry.platform(command.actor(), "tenant.created", AggregateTargets.tenant(tenant.id()), command.requestId(), now));
            return new Provisioned(tenant, domain);
        });
        registerConsoleHost(provisioned.domain());
        return provisioned.tenant();
    }

    private void registerConsoleHost(TenantDomain domain) {
        try {
            consoleHosts.register(domain.hostname().value());
        } catch (RuntimeException failure) {
            // The tenant exists; the reconciler makes the client equal the hosts that exist, so a Keycloak that is
            // down now must not fail a request the Platform has already accepted.
            LOG.log(System.Logger.Level.WARNING,
                    "the console redirect sync will repair host " + domain.hostname().value(), failure);
        }
    }
}
