package dev.ngb.mailer.email.domain.delivery;

import dev.ngb.core.util.Backoff;
import java.time.Duration;
import java.util.Objects;

/**
 * How often a delivery is tried, how long between tries, and how long a worker may hold one.
 *
 * <p>It is a domain object rather than a reuse of the broker relay's configuration record because this channel has a
 * third answer — {@code Rejected}, which is terminal immediately — that publish-or-fail does not have. It copies the
 * relay's field vocabulary and its jittered backoff so the two cannot drift apart conceptually, without making the
 * delivery job depend on the broker's configuration.
 *
 * <p>{@code sendDeadline} is the worst case one send may take: connect plus read plus write timeout. The
 * construction-time check that the lease exceeds it is the invariant that keeps a second worker from claiming a
 * delivery whose first send is still in flight.
 */
public record DeliveryPolicy(int maxAttempts, Backoff backoff, Duration lease, Duration sendDeadline) {

    public DeliveryPolicy {
        Objects.requireNonNull(backoff, "backoff");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(sendDeadline, "sendDeadline");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        if (sendDeadline.isNegative()) {
            throw new IllegalArgumentException("sendDeadline must not be negative");
        }
        if (lease.compareTo(sendDeadline) <= 0) {
            throw new IllegalArgumentException(
                    "the lease (" + lease + ") must exceed the worst-case send deadline (" + sendDeadline
                            + "), or a second worker claims a delivery whose send is still running");
        }
    }

    /** True when {@code attemptsMade} attempts have used up the budget. */
    public boolean exhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    public Duration retryDelay(int attemptsMade, double random) {
        return backoff.delayAfter(attemptsMade, random);
    }
}
