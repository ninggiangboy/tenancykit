package dev.ngb.loyalty.webhook.application.endpoint.service;

import dev.ngb.core.webhook.RetrySchedule;
import java.time.Duration;
import java.util.Objects;

/**
 * Deployment settings of the webhook module. {@code allowNonPublicTargets} is {@code false} everywhere except the
 * compose profile: it lets a delivery reach a loopback or private address, which is a development receiver and never a
 * Tenant's endpoint, and leaving it on in a deployment would let an endpoint reach the suite's own services.
 *
 * @param retention how long a finished delivery and its attempt log are kept before housekeeping purges them
 */
public record WebhookSettings(
        boolean allowNonPublicTargets, int batchSize, RetrySchedule policy, Duration retention) {

    public WebhookSettings {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(retention, "retention");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
    }

    /** Settings a test uses: no non-public targets, a short retry policy and a short retention. */
    public static WebhookSettings strictForTests() {
        return new WebhookSettings(
                false,
                10,
                new RetrySchedule(
                        3,
                        new dev.ngb.core.util.Backoff(Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2),
                        Duration.ofMinutes(1)),
                Duration.ofDays(30));
    }

    /** Settings a test uses when its receiver is a local address: the one difference is the destination policy. */
    public static WebhookSettings forLocalReceiver(int maxAttempts, Duration backoffBase) {
        return new WebhookSettings(
                true,
                10,
                new RetrySchedule(
                        maxAttempts,
                        new dev.ngb.core.util.Backoff(backoffBase, backoffBase, 0.0),
                        Duration.ofMinutes(1)),
                Duration.ofDays(30));
    }
}
