package dev.ngb.workflow.webhook.application.endpoint.service;

import dev.ngb.core.webhook.RetrySchedule;
import java.util.Objects;

/**
 * Deployment settings of the webhook module. {@code allowInsecureTargets} is for development only: it allows
 * http and private addresses so a local receiver can be used.
 */
public record WebhookSettings(boolean allowInsecureTargets, int batchSize, RetrySchedule policy) {

    public WebhookSettings {
        Objects.requireNonNull(policy, "policy");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
    }

    /** Settings a test uses: no insecure targets, a short retry policy. */
    public static WebhookSettings strictForTests() {
        return new WebhookSettings(
                false,
                10,
                new RetrySchedule(
                        3,
                        new dev.ngb.core.util.Backoff(java.time.Duration.ofSeconds(1), java.time.Duration.ofMinutes(1), 0.2),
                        java.time.Duration.ofMinutes(1)));
    }
}
