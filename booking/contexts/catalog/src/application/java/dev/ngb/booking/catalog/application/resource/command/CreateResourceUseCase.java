package dev.ngb.booking.catalog.application.resource.command;

import dev.ngb.booking.catalog.application.catalog.exception.DuplicateCodeException;
import dev.ngb.booking.catalog.application.resource.port.ResourceRepository;
import dev.ngb.booking.catalog.application.resource.service.ResourceRules;
import dev.ngb.booking.catalog.domain.resource.Resource;
import dev.ngb.booking.catalog.domain.resource.ResourceId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;

/**
 * {@code POST /resources}: the Tenant names one unit it may book, in the zone its wall-clock hours are read in.
 *
 * <p>The code is the natural key, unique per tenant and immutable, so a second resource with the same code is a
 * conflict rather than a duplicate.
 */
public class CreateResourceUseCase {

    /** One resource as the Tenant sent it; a null seat count means the resource is not seat-limited. */
    public record Command(String code, String name, String zone, Integer seats) {}

    private final UnitOfWork unitOfWork;
    private final ResourceRepository resources;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateResourceUseCase(
            UnitOfWork unitOfWork,
            ResourceRepository resources,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.resources = resources;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    /** @throws DuplicateCodeException when the tenant already chose this code */
    public Resource execute(Command command) {
        String code = ResourceRules.code(command.code());
        String name = ResourceRules.name(command.name());
        String zone = ResourceRules.zone(command.zone());
        Integer seats = ResourceRules.seats(command.seats());
        return unitOfWork.transactional(() -> {
            if (resources.existsByCode(code)) {
                throw new DuplicateCodeException(code);
            }
            Resource resource = Resource.create(
                    new ResourceId(ids.newId()), tenantContext.requireTenant(), code, name, zone, seats, clock.now());
            resources.insert(resource);
            return resource;
        });
    }
}
