package dev.ngb.booking.catalog.application.resource.command;

import dev.ngb.booking.catalog.application.resource.port.ResourceRepository;
import dev.ngb.booking.catalog.application.resource.service.ResourceRules;
import dev.ngb.booking.catalog.domain.resource.Resource;
import dev.ngb.booking.catalog.domain.resource.ResourceStatus;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import java.util.UUID;

/**
 * {@code PATCH /resources/{resourceId}}: only the properties the caller sent change, and only if the resource is still
 * the version the caller read. A null property means "leave this one alone"; a status of {@code ARCHIVED} archives the
 * resource, and the code and the zone never change.
 */
public class PatchResourceUseCase {

    /** A change request: a null property is left as it is. */
    public record Change(String name, Integer seats, ResourceStatus status) {}

    private static final String UNKNOWN_RESOURCE = "No resource exists here with that id.";

    private final UnitOfWork unitOfWork;
    private final ResourceRepository resources;
    private final Clock clock;

    public PatchResourceUseCase(UnitOfWork unitOfWork, ResourceRepository resources, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.resources = resources;
        this.clock = clock;
    }

    /** @throws NotFoundException when the resource does not exist here */
    public Resource execute(UUID id, Change change, ExpectedVersion expected) {
        String name = change.name() == null ? null : ResourceRules.name(change.name());
        Integer seats = change.seats() == null ? null : ResourceRules.seats(change.seats());
        return unitOfWork.transactional(() -> {
            Resource resource = resources.lockById(id).orElseThrow(() -> new NotFoundException(UNKNOWN_RESOURCE));
            expected.check(resource.version());
            boolean changed = false;
            if (name != null) {
                changed |= resource.rename(name);
            }
            if (seats != null) {
                changed |= resource.resize(seats);
            }
            if (change.status() == ResourceStatus.ARCHIVED) {
                changed |= resource.archive();
            }
            if (changed) {
                resource.touch(clock.now());
                resources.update(resource);
            }
            return resource;
        });
    }
}
