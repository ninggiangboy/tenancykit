package dev.ngb.loyalty.points.application.ledger.port;

import java.time.Instant;
import java.util.UUID;

/** A lot to create from an earn: what the entry granted it and when it expires. */
public record PointLotRow(
        UUID id,
        UUID memberId,
        UUID entryId,
        UUID orderId,
        long earned,
        Instant earnedAt,
        Instant expiresAt) {

    public PointLotRow {
        if (earned <= 0) {
            throw new IllegalArgumentException("a lot is created with at least one point");
        }
        if (!expiresAt.isAfter(earnedAt)) {
            throw new IllegalArgumentException("a lot expires after it was earned");
        }
    }
}
