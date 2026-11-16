package dev.ngb.loyalty.points.application.expiry.port;

import java.time.Instant;
import java.util.UUID;

/**
 * A lot still holding points that the expiry or the notice run has to act on: what it holds and when it expires.
 *
 * <p>It is read under a row lock, so the points here are the ones the flow writes off or announces; a lot another flow
 * emptied in the meantime has nothing left and is skipped.
 */
public record DueLot(UUID lotId, long remaining, Instant expiresAt) {

    public DueLot {
        if (remaining < 0) {
            throw new IllegalArgumentException("a lot never holds fewer than zero points");
        }
    }
}
