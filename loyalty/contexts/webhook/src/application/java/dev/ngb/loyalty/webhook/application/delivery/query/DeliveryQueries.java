package dev.ngb.loyalty.webhook.application.delivery.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;

/** The delivery history a Tenant reads, narrowed and paged the way the list-query contract asks. */
public class DeliveryQueries {

    private final UnitOfWork unitOfWork;
    private final DeliveryReadRepository deliveries;

    public DeliveryQueries(UnitOfWork unitOfWork, DeliveryReadRepository deliveries) {
        this.unitOfWork = unitOfWork;
        this.deliveries = deliveries;
    }

    public Page<WebhookDeliveryView> list(ListQuery query) {
        return unitOfWork.transactional(() -> deliveries.list(query));
    }
}
