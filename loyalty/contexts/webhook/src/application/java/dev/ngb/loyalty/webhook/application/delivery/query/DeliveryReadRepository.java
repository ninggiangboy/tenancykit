package dev.ngb.loyalty.webhook.application.delivery.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;

/** Read-only projections of deliveries. */
public interface DeliveryReadRepository {

    /** One page of the delivery history, newest first unless the caller sorts otherwise. */
    Page<WebhookDeliveryView> list(ListQuery query);
}
