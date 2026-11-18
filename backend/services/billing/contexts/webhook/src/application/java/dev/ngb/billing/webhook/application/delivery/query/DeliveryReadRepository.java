package dev.ngb.billing.webhook.application.delivery.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of deliveries and their attempt log. */
public interface DeliveryReadRepository {

    /** One page of deliveries; {@code tenantSystemId} narrows it to the system's endpoints, and null reads the tenant. */
    Page<WebhookDeliveryView> list(ListQuery query, UUID tenantSystemId);

    Optional<WebhookDeliveryView> findById(UUID id, UUID tenantSystemId);

    List<DeliveryAttemptView> attempts(UUID deliveryId);
}
