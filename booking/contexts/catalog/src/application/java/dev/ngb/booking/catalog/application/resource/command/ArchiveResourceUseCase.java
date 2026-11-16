package dev.ngb.booking.catalog.application.resource.command;

import dev.ngb.booking.catalog.application.resource.port.ResourceRepository;
import dev.ngb.booking.catalog.domain.resource.Resource;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import java.util.UUID;

/**
 * {@code DELETE /resources/{resourceId}}: a referenced resource is archived and never deleted, because an offering's
 * requirements and a group's membership point at it; an unreferenced one is deleted outright.
 *
 * <p>The call is idempotent: an archived, unreferenced resource is deleted by the first call and answered the same way
 * by a retry, and an archived resource something still references is left as it is.
 */
public class ArchiveResourceUseCase {

    private static final String UNKNOWN_RESOURCE = "No resource exists here with that id.";

    private final UnitOfWork unitOfWork;
    private final ResourceRepository resources;
    private final Clock clock;

    public ArchiveResourceUseCase(UnitOfWork unitOfWork, ResourceRepository resources, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.resources = resources;
        this.clock = clock;
    }

    /** @throws NotFoundException when the resource does not exist here */
    public void execute(UUID id, ExpectedVersion expected) {
        unitOfWork.transactional(() -> {
            Resource resource = resources.lockById(id).orElseThrow(() -> new NotFoundException(UNKNOWN_RESOURCE));
            expected.check(resource.version());
            // A reference can appear between the check and the delete, so a delete that finds one archives instead.
            if (resources.isReferenced(id) || !resources.deleteIfUnreferenced(id)) {
                if (resource.archive()) {
                    resource.touch(clock.now());
                    resources.archive(resource);
                }
            }
        });
    }
}
