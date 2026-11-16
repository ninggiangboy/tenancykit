package dev.ngb.survey.webhook.application.delivery.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.PageSize;
import dev.ngb.survey.webhook.domain.delivery.DeliveryStatus;
import java.util.List;
import java.util.UUID;

/**
 * The delivery history a Tenant reads: newest first, optionally narrowed by status, endpoint or the last id of the
 * previous page.
 */
public class DeliveryQueries {

    /** One delivery and the attempts that were made for it. */
    public record Detail(WebhookDeliveryView delivery, List<DeliveryAttemptView> attempts) {}

    private final UnitOfWork unitOfWork;
    private final DeliveryReadRepository deliveries;

    public DeliveryQueries(UnitOfWork unitOfWork, DeliveryReadRepository deliveries) {
        this.unitOfWork = unitOfWork;
        this.deliveries = deliveries;
    }

    public List<WebhookDeliveryView> list(
            DeliveryStatus status, UUID endpointId, UUID before, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return unitOfWork.transactional(() -> deliveries.list(status, endpointId, before, PageSize.of(limit)));
    }

    public Detail get(UUID id) {
        return unitOfWork.transactional(() -> deliveries.findById(id)
                .map(delivery -> new Detail(delivery, deliveries.attempts(id)))
                .orElseThrow(() -> new NotFoundException("No webhook delivery exists here.")));
    }
}
