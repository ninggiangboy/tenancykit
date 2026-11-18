package dev.ngb.survey.webhook.application.delivery.command;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.webhook.application.delivery.port.WebhookDeliveryRepository;
import dev.ngb.survey.webhook.domain.delivery.WebhookDelivery;
import dev.ngb.survey.webhook.domain.delivery.WebhookDeliveryId;
import java.time.Instant;

/**
 * Requeues a stored event to the endpoint that received it, with a fresh attempt budget.
 *
 * <p>The original keeps its history and the new row is the same body, so a receiver sees exactly what the first attempt
 * carried. Only a finished delivery can be resent: one still being attempted is already on its way.
 */
public class ManageDeliveryUseCase {

    private final UnitOfWork unitOfWork;
    private final WebhookDeliveryRepository deliveries;
    private final IdGenerator ids;
    private final Clock clock;

    public ManageDeliveryUseCase(
            UnitOfWork unitOfWork,
            WebhookDeliveryRepository deliveries,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.deliveries = deliveries;
        this.ids = ids;
        this.clock = clock;
    }

    public WebhookDelivery resend(WebhookDeliveryId id) {
        return unitOfWork.transactional(() -> {
            WebhookDelivery original = deliveries.findById(id)
                    .orElseThrow(() -> new NotFoundException("No webhook delivery exists here."));
            if (!original.isFinished()) {
                throw new ConflictException("this delivery is still being attempted");
            }
            Instant now = clock.now();
            WebhookDelivery copy = WebhookDelivery.queue(
                    new WebhookDeliveryId(ids.newId()),
                    original.tenantId(),
                    original.endpointId(),
                    original.eventId(),
                    original.eventType(),
                    original.body(),
                    now);
            deliveries.add(copy);
            return copy;
        });
    }
}
