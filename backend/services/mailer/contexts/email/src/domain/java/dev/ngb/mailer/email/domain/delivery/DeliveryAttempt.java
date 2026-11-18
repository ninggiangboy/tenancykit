package dev.ngb.mailer.email.domain.delivery;

import java.time.Instant;
import java.util.Objects;

/**
 * What one attempt came back with. The attempt log is append-only: a delivery's history is evidence, and the row's
 * current status is only its last line.
 */
public record DeliveryAttempt(int attemptNo, Outcome outcome, String error, Instant attemptedAt) {

    /** The three answers a transport can give. Each maps to exactly one ledger transition. */
    public enum Outcome {
        /** The message was accepted for delivery. */
        ACCEPTED,
        /** The request itself is wrong; retrying only burns quota. */
        REJECTED,
        /** A condition that may pass: throttling, an outage, a timeout. */
        TRANSIENT
    }

    public DeliveryAttempt {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(attemptedAt, "attemptedAt");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo starts at 1");
        }
    }
}
