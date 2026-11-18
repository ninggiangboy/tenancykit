package dev.ngb.workflow.webhook.application.delivery.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** The delivery history a Tenant reads, narrowed and paged the way the list-query contract asks. */
public class DeliveryQueries {

    /** One delivery and the attempts that were made for it. */
    public record Detail(WebhookDeliveryView delivery, List<DeliveryAttemptView> attempts) {}

    private final UnitOfWork unitOfWork;
    private final DeliveryReadRepository deliveries;

    public DeliveryQueries(UnitOfWork unitOfWork, DeliveryReadRepository deliveries) {
        this.unitOfWork = unitOfWork;
        this.deliveries = deliveries;
    }

    public Page<WebhookDeliveryView> list(ListQuery query) {
        return unitOfWork.transactional(() -> deliveries.list(query));
    }

    public Detail get(UUID id) {
        return unitOfWork.transactional(() -> deliveries.findById(id)
                .map(delivery -> new Detail(delivery, deliveries.attempts(id)))
                .orElseThrow(() -> new NotFoundException("No webhook delivery exists here.")));
    }
}
