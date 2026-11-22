package dev.ngb.booking.catalog.application.resource.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** Reads the current tenant's resources for the GET handlers. */
public class ResourceQueries {

    private final UnitOfWork unitOfWork;
    private final ResourceReadRepository resources;

    public ResourceQueries(UnitOfWork unitOfWork, ResourceReadRepository resources) {
        this.unitOfWork = unitOfWork;
        this.resources = resources;
    }

    public Page<ResourceView> list(ListQuery query) {
        return unitOfWork.transactional(() -> resources.list(query));
    }

    /** @throws NotFoundException when no resource of this tenant has the id */
    public ResourceView get(UUID id) {
        return unitOfWork.transactional(() -> resources
                .findById(id)
                .orElseThrow(() -> new NotFoundException("No resource exists here with that id.")));
    }
}
