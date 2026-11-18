package dev.ngb.billing.webhook.application.endpoint.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.UUID;

/** Reads the current tenant's endpoints for the admin GET handlers. */
public class EndpointQueries {

    private final UnitOfWork unitOfWork;
    private final EndpointReadRepository endpoints;

    public EndpointQueries(UnitOfWork unitOfWork, EndpointReadRepository endpoints) {
        this.unitOfWork = unitOfWork;
        this.endpoints = endpoints;
    }

    /** A Tenant System sees only its own endpoints; any other caller sees the whole tenant. */
    public Page<WebhookEndpointView> list(ListQuery query, UUID caller) {
        return unitOfWork.transactional(() -> endpoints.list(query, caller));
    }

    public WebhookEndpointView get(UUID id) {
        return get(id, null);
    }

    public WebhookEndpointView get(UUID id, UUID caller) {
        return unitOfWork.transactional(
                () -> endpoints.findById(id, caller).orElseThrow(() -> new NotFoundException("webhook endpoint not found")));
    }
}
