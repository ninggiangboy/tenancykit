package dev.ngb.crm.webhook.application.endpoint.port;

import dev.ngb.crm.webhook.domain.endpoint.WebhookEndpoint;
import dev.ngb.crm.webhook.domain.endpoint.WebhookEndpointId;
import java.util.List;
import java.util.Optional;

/** Endpoints of the current tenant. */
public interface WebhookEndpointRepository {

    void add(WebhookEndpoint endpoint);

    void update(WebhookEndpoint endpoint);

    Optional<WebhookEndpoint> findById(WebhookEndpointId id);

    /** Marks the endpoint deleted: it stops receiving and leaves every list, while its deliveries stay readable. */
    void delete(WebhookEndpoint endpoint);

    /** Reads the endpoint under a row lock, so the update that follows cannot race another one. */
    Optional<WebhookEndpoint> lockById(WebhookEndpointId id);

    /** Active endpoints that want {@code eventType}, directly or through the all-events subscription. */
    List<WebhookEndpoint> findSubscribed(String eventType);
}
