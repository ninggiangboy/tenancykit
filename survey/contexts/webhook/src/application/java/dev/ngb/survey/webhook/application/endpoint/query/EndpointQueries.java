package dev.ngb.survey.webhook.application.endpoint.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** Reads the current tenant's endpoints for the admin GET handlers. */
public class EndpointQueries {

    private final UnitOfWork unitOfWork;
    private final EndpointReadRepository endpoints;

    public EndpointQueries(UnitOfWork unitOfWork, EndpointReadRepository endpoints) {
        this.unitOfWork = unitOfWork;
        this.endpoints = endpoints;
    }

    public List<WebhookEndpointView> list() {
        return unitOfWork.transactional(endpoints::list);
    }

    public WebhookEndpointView get(UUID id) {
        return unitOfWork.transactional(
                () -> endpoints.findById(id).orElseThrow(() -> new NotFoundException("No webhook endpoint exists here.")));
    }
}
