package dev.ngb.booking.inventory.application.interval.service;

import dev.ngb.booking.inventory.domain.interval.ClaimRange;
import java.time.Instant;

/**
 * How a claim's service range becomes the range the database holds: the buffers are folded in, so the claim a
 * customer never sees the buffer of is what no other booking may overlap.
 */
public class IntervalClaimRules {

    /** @return the buffered range {@code [start - bufferBefore, end + bufferAfter)} */
    public ClaimRange bufferedRange(
            Instant start, Instant end, long bufferBeforeSeconds, long bufferAfterSeconds) {
        if (bufferBeforeSeconds < 0 || bufferAfterSeconds < 0) {
            throw new IllegalArgumentException("a buffer is never negative");
        }
        return new ClaimRange(start.minusSeconds(bufferBeforeSeconds), end.plusSeconds(bufferAfterSeconds));
    }

    /** @return when a hold lapses, once its grace is added, or null for a claim that does not expire */
    public Instant expiredAt(Instant expiresAt, long graceSeconds) {
        if (graceSeconds < 0) {
            throw new IllegalArgumentException("a grace is never negative");
        }
        return expiresAt == null ? null : expiresAt.plusSeconds(graceSeconds);
    }
}
