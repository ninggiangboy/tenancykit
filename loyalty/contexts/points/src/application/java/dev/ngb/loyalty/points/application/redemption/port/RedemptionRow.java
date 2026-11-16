package dev.ngb.loyalty.points.application.redemption.port;

import dev.ngb.loyalty.points.domain.redemption.RedemptionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A redemption row as the writing side reads and writes it: what was spent, for what the Tenant asked, and whether a
 * reversal restored it.
 */
public record RedemptionRow(
        UUID id,
        UUID memberId,
        String idempotencyKey,
        String fingerprint,
        long points,
        String reference,
        RedemptionStatus status,
        Instant createdAt,
        Instant reversedAt) {

    public static RedemptionRow active(
            UUID id,
            UUID memberId,
            String idempotencyKey,
            String fingerprint,
            long points,
            String reference,
            Instant createdAt) {
        return new RedemptionRow(
                id, memberId, idempotencyKey, fingerprint, points, reference, RedemptionStatus.ACTIVE, createdAt, null);
    }

    public boolean reversed() {
        return status == RedemptionStatus.REVERSED;
    }
}
