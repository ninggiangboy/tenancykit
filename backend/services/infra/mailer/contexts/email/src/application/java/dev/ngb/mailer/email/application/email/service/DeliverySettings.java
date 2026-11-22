package dev.ngb.mailer.email.application.email.service;

import dev.ngb.mailer.email.domain.delivery.DeliveryPolicy;
import java.time.Duration;
import java.util.Objects;

/**
 * The delivery round's tuning, read once from configuration and handed to the use case, so the use case never reads a
 * property source.
 *
 * @param pollDelay how often the job runs; carried here so the job's scheduling and the round's policy are configured
 *     in one place
 */
public record DeliverySettings(boolean enabled, int batchSize, Duration pollDelay, DeliveryPolicy policy) {

    public DeliverySettings {
        Objects.requireNonNull(pollDelay, "pollDelay");
        Objects.requireNonNull(policy, "policy");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
    }
}
