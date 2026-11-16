package dev.ngb.survey.webhook.application.delivery.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of deliveries and their attempt log. */
public interface DeliveryReadRepository {

    /** One page of the delivery history, newest first unless the caller sorts otherwise. */
    Page<WebhookDeliveryView> list(ListQuery query);

    Optional<WebhookDeliveryView> findById(UUID id);

    List<DeliveryAttemptView> attempts(UUID deliveryId);
}
