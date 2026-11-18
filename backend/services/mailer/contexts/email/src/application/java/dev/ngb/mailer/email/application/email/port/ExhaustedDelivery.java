package dev.ngb.mailer.email.application.email.port;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A delivery a worker left in flight and that has now used up its budget: {@code killExhausted} sets it {@code DEAD}
 * and returns it so the same transaction can publish the terminal result. Without this the producer's ledger would
 * never learn the outcome of a delivery whose worker died on its final attempt.
 *
 * @param completedAt when the sweep declared it dead, which is also the result event's timestamp
 * @param lastError the reason already on the row, or the sweep's own explanation
 */
public record ExhaustedDelivery(
        UUID deliveryId,
        String requestId,
        String recipient,
        int attempts,
        Instant completedAt,
        String lastError) {

    public ExhaustedDelivery {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
