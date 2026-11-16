package dev.ngb.loyalty.points.domain.earn;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What one line contributed to an order's points: its multiplier, the eligible amount it was applied to and the
 * promotions behind the multiplier.
 */
public record LineContribution(int lineNo, BigDecimal multiplier, long eligibleAmount, List<UUID> promotionIds) {

    public LineContribution {
        promotionIds = promotionIds == null ? List.of() : List.copyOf(promotionIds);
    }
}
