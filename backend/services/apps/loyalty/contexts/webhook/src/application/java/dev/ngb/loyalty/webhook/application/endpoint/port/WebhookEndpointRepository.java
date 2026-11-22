package dev.ngb.loyalty.webhook.application.endpoint.port;

import dev.ngb.loyalty.webhook.domain.endpoint.WebhookEndpoint;
import dev.ngb.loyalty.webhook.domain.endpoint.WebhookEndpointId;
import java.util.List;
import java.util.Optional;

/** Endpoints of the current tenant. */
public interface WebhookEndpointRepository {

    void add(WebhookEndpoint endpoint);

    void update(WebhookEndpoint endpoint);

    Optional<WebhookEndpoint> findById(WebhookEndpointId id);

    /** Reads the endpoint under a row lock, so the update that follows cannot race another one. */
    Optional<WebhookEndpoint> lockById(WebhookEndpointId id);

    /** Active endpoints that want {@code eventType}, directly or through the all-events subscription. */
    List<WebhookEndpoint> findSubscribed(String eventType);

    /**
     * Removes the endpoint. Its delivery history stays: a delivery already recorded is evidence of what was sent, and
     * the rows still owed to it are abandoned before the row disappears.
     */
    void delete(WebhookEndpointId id);
}
