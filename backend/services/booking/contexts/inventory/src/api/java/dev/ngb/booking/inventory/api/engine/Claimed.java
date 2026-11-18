package dev.ngb.booking.inventory.api.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A claim that happened.
 *
 * @param claimId the engine's own row, so a release can be traced to what it freed
 * @param expiresAt when the hold lapses, or null for a committed claim
 */
public record Claimed(UUID bookingId, UUID claimId, Instant expiresAt) implements ClaimOutcome {

    public Claimed {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(claimId, "claimId");
    }
}
