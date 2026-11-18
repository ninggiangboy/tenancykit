package dev.ngb.customer.webhook.application.delivery.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.customer.webhook.application.delivery.port.WebhookDeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Retention for the delivery history: deletes finished deliveries and their attempt log once they are older than the
 * deployment's retention. A delivery still {@code PENDING} or {@code IN_PROGRESS} is never touched, so a slow retry
 * survives housekeeping.
 *
 * <p>The cutoff comes from the caller's clock and is passed in, so a test can age a row instead of waiting.
 */
public class PurgeDeliveriesUseCase {

    private final UnitOfWork unitOfWork;
    private final WebhookDeliveryRepository deliveries;
    private final Duration retention;
    private final int batchSize;

    public PurgeDeliveriesUseCase(
            UnitOfWork unitOfWork, WebhookDeliveryRepository deliveries, Duration retention, int batchSize) {
        this.unitOfWork = unitOfWork;
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.batchSize = batchSize;
    }

    /** @return how many finished deliveries were purged */
    public int runOnce(Instant now) {
        Instant cutoff = now.minus(retention);
        return unitOfWork.transactional(() -> deliveries.purgeFinished(cutoff, batchSize));
    }
}
