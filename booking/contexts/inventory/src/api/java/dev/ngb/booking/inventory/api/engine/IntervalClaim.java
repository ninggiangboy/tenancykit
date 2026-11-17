package dev.ngb.booking.inventory.api.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A range on one resource. The claimed range is {@code [start - bufferBefore, end + bufferAfter)}: a buffer belongs to
 * the claim even though the service the customer sees is only {@code [start, end)}.
 *
 * @param expiresAt when the claim lapses, or null for a claim that does not expire
 * @param now the clock the engine reads, so a test can freeze it
 * @param graceSeconds how long past {@code expiresAt} a claim still blocks before it is reclaimed
 */
public record IntervalClaim(
        UUID bookingId,
        UUID resourceId,
        Instant start,
        Instant end,
        long bufferBeforeSeconds,
        long bufferAfterSeconds,
        Instant expiresAt,
        Instant now,
        long graceSeconds)
        implements ClaimRequest {

    public IntervalClaim {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(now, "now");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("a claim's start is before its end");
        }
        if (bufferBeforeSeconds < 0 || bufferAfterSeconds < 0 || graceSeconds < 0) {
            throw new IllegalArgumentException("a buffer and a grace are never negative");
        }
    }
}
