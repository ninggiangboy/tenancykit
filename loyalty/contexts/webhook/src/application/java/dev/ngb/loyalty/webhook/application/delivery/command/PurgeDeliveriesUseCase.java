package dev.ngb.loyalty.webhook.application.delivery.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.webhook.application.delivery.port.WebhookDeliveryRepository;
import dev.ngb.loyalty.webhook.application.endpoint.service.WebhookSettings;
import java.time.Instant;

/**
 * Bounded retention for what is already finished: a succeeded or dead delivery and its attempt log are kept long enough
 * to answer a support question and then purged, so the tables do not grow without bound. A retry copies a delivery
 * before this can remove it, so a Tenant still has its history to resend from.
 */
public class PurgeDeliveriesUseCase {

    private final UnitOfWork unitOfWork;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookSettings settings;
    private final Clock clock;

    public PurgeDeliveriesUseCase(
            UnitOfWork unitOfWork, WebhookDeliveryRepository deliveries, WebhookSettings settings, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.deliveries = deliveries;
        this.settings = settings;
        this.clock = clock;
    }

    /** @return how many deliveries were purged in this call */
    public int execute(int limit) {
        return unitOfWork.transactional(() -> {
            Instant before = clock.now().minus(settings.retention());
            return deliveries.purgeFinished(before, limit);
        });
    }
}
