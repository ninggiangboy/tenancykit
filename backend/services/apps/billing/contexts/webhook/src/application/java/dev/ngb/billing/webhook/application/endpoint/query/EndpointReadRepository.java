package dev.ngb.billing.webhook.application.endpoint.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of the current tenant's endpoints. */
public interface EndpointReadRepository {

    /** One page of endpoints; a null system means the Tenant User's view, every endpoint of the tenant. */
    Page<WebhookEndpointView> list(ListQuery query, UUID tenantSystemId);

    Optional<WebhookEndpointView> findById(UUID id, UUID tenantSystemId);
}
