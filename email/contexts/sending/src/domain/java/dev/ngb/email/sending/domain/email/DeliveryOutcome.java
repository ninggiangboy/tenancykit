package dev.ngb.email.sending.domain.email;

import java.time.Instant;
import java.util.Objects;

/**
 * The terminal outcome mailer reported for one recipient. `status` is an open string: this app knows
 * {@code SUCCEEDED} and {@code DEAD} and ignores anything else, because a later mailer may add {@code BOUNCED}.
 */
public record DeliveryOutcome(
        String requestId,
        String status,
        int attempts,
        Instant completedAt,
        String providerMessageId,
        String lastError) {

    public DeliveryOutcome {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(completedAt, "completedAt");
    }

    public boolean succeeded() {
        return "SUCCEEDED".equals(status);
    }

    public boolean dead() {
        return "DEAD".equals(status);
    }
}
