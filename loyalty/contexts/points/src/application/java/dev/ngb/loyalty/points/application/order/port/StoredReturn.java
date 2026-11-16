package dev.ngb.loyalty.points.application.order.port;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One recorded return and what it took back. The two point figures are stored with the return, so an order read can show
 * what each return did without replaying the whole history of the order.
 */
public record StoredReturn(
        UUID id,
        UUID orderId,
        String returnRef,
        Instant occurredAt,
        String fingerprint,
        long pointsClawedBack,
        long debtAdded,
        Instant createdAt) {

    public StoredReturn {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(returnRef, "returnRef");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(createdAt, "createdAt");
        if (pointsClawedBack < 0 || debtAdded < 0) {
            throw new IllegalArgumentException("what a return took back is never negative");
        }
    }

    public StoredReturn takingBack(long points, long debt) {
        return new StoredReturn(id, orderId, returnRef, occurredAt, fingerprint, points, debt, createdAt);
    }
}
