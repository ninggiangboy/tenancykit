package dev.ngb.platform.tenancy.application.tenant.command;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.tenancy.application.tenant.port.TenantDomainRepository;
import dev.ngb.platform.tenancy.domain.tenant.Hostname;
import dev.ngb.platform.tenancy.domain.tenant.TenantDomain;
import dev.ngb.platform.tenancy.domain.tenant.TenancyEvents;
import java.time.Instant;
import java.util.Optional;

/** Makes sure the Platform's own hostname resolves, at startup and idempotently. */
public class EnsurePlatformDomainUseCase {

    private final UnitOfWork unitOfWork;
    private final TenantDomainRepository domains;
    private final EventPublisher events;
    private final IdGenerator ids;
    private final Clock clock;

    public EnsurePlatformDomainUseCase(
            UnitOfWork unitOfWork, TenantDomainRepository domains, EventPublisher events, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.domains = domains;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    /** @return the existing or newly created platform hostname */
    public TenantDomain execute(String hostname) {
        return unitOfWork.transactional(() -> {
            Hostname name = Hostname.fromHostHeader(hostname);
            Optional<TenantDomain> existing = domains.findByHostname(name);
            if (existing.isPresent()) {
                return existing.get();
            }
            Instant now = clock.now();
            TenantDomain domain = TenantDomain.platform(ids.newId(), name, now);
            domains.add(domain);
            events.publish(new TenancyEvents.TenantDomainChanged(ids.newId(), domain, now));
            return domain;
        });
    }
}
