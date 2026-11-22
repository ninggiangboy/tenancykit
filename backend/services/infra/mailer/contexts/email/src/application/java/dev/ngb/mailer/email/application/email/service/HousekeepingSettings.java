package dev.ngb.mailer.email.application.email.service;

import java.time.Duration;
import java.util.Objects;

/**
 * What the housekeeping round keeps and for how long. Retention is a requirement of the plan, not a nicety: the body
 * carries personal data, and the inbox carries every event the consumer has ever seen.
 *
 * @param bodyRetention how long a body is kept after it was written; the ledger row outlives it, which is what makes a
 *     dead delivery resendable inside a short window
 */
public record HousekeepingSettings(
        boolean enabled, Duration bodyRetention, Duration inboxRetention, int batchSize, Duration interval) {

    public HousekeepingSettings {
        Objects.requireNonNull(bodyRetention, "bodyRetention");
        Objects.requireNonNull(inboxRetention, "inboxRetention");
        Objects.requireNonNull(interval, "interval");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
    }
}
