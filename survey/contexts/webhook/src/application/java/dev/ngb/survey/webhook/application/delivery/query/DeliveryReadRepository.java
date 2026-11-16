package dev.ngb.survey.webhook.application.delivery.query;

import dev.ngb.survey.webhook.domain.delivery.DeliveryStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of deliveries and their attempt log. */
public interface DeliveryReadRepository {

    /** Newest first; {@code before} is the id of the last row of the previous page. A null system sees every endpoint. */
    List<WebhookDeliveryView> list(DeliveryStatus status, UUID endpointId, UUID before, int limit);

    Optional<WebhookDeliveryView> findById(UUID id);

    List<DeliveryAttemptView> attempts(UUID deliveryId);
}
