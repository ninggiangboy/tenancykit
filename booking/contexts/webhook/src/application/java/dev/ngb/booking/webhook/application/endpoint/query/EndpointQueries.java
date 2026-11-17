package dev.ngb.booking.webhook.application.endpoint.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** Reads the current tenant's endpoints for the admin GET handlers. */
public class EndpointQueries {

    private final UnitOfWork unitOfWork;
    private final EndpointReadRepository endpoints;

    public EndpointQueries(UnitOfWork unitOfWork, EndpointReadRepository endpoints) {
        this.unitOfWork = unitOfWork;
        this.endpoints = endpoints;
    }

    public Page<WebhookEndpointView> list(ListQuery query) {
        return unitOfWork.transactional(() -> endpoints.list(query));
    }

    public WebhookEndpointView get(UUID id) {
        return unitOfWork.transactional(
                () -> endpoints.findById(id).orElseThrow(() -> new NotFoundException("No webhook endpoint exists here.")));
    }
}
