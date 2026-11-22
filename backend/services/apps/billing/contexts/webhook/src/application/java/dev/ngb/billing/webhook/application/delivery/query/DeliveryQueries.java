package dev.ngb.billing.webhook.application.delivery.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.UUID;

/** Reads delivery history for the admin GET handlers; the write side is not involved. */
public class DeliveryQueries {


    public record Detail(WebhookDeliveryView delivery, List<DeliveryAttemptView> attempts) {}

    private final UnitOfWork unitOfWork;
    private final DeliveryReadRepository deliveries;

    public DeliveryQueries(UnitOfWork unitOfWork, DeliveryReadRepository deliveries) {
        this.unitOfWork = unitOfWork;
        this.deliveries = deliveries;
    }

    /** A Tenant System sees only the deliveries of its own endpoints; any other caller sees the whole tenant. */
    public Page<WebhookDeliveryView> list(ListQuery query, UUID caller) {
        return unitOfWork.transactional(() -> deliveries.list(query, caller));
    }

    public Detail get(UUID id) {
        return get(id, null);
    }

    public Detail get(UUID id, UUID caller) {
        return unitOfWork.transactional(() -> deliveries.findById(id, caller)
                .map(delivery -> new Detail(delivery, deliveries.attempts(id)))
                .orElseThrow(() -> new NotFoundException("webhook delivery not found")));
    }
}
