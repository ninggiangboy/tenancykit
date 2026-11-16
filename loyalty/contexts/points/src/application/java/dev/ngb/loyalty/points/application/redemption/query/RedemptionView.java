package dev.ngb.loyalty.points.application.redemption.query;

import java.time.Instant;
import java.util.UUID;

/** One redemption as the read side answers it, with the member's balance and debt at the moment of the read. */
public record RedemptionView(
        UUID redemptionId,
        String externalRef,
        long points,
        String reference,
        String status,
        long available,
        long debt,
        Instant createdAt,
        Instant reversedAt) {}
