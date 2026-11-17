package dev.ngb.booking.webhook.application.endpoint.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of the current tenant's endpoints. */
public interface EndpointReadRepository {

    /** One page of the tenant's endpoints, oldest first unless the caller sorts otherwise. */
    Page<WebhookEndpointView> list(ListQuery query);

    Optional<WebhookEndpointView> findById(UUID id);
}
