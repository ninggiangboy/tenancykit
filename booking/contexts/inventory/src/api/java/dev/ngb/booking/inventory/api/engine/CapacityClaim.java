package dev.ngb.booking.inventory.api.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A number of places in one pool of one session, all or nothing.
 *
 * @param expiresAt when the hold lapses, or null for a committed claim
 * @param now the clock the engine reads, so a test can freeze it
 * @param graceSeconds how long past {@code expiresAt} a hold still blocks before it is reclaimed
 */
public record CapacityClaim(
        UUID bookingId,
        UUID sessionId,
        String poolCode,
        int qty,
        Instant expiresAt,
        Instant now,
        long graceSeconds)
        implements ClaimRequest {

    public CapacityClaim {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(poolCode, "poolCode");
        Objects.requireNonNull(now, "now");
        if (qty < 1) {
            throw new IllegalArgumentException("a claim takes at least one place");
        }
        if (graceSeconds < 0) {
            throw new IllegalArgumentException("a grace is never negative");
        }
    }
}
