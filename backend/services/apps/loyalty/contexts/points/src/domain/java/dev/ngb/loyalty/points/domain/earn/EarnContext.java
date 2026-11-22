package dev.ngb.loyalty.points.domain.earn;

import dev.ngb.loyalty.points.domain.order.OrderLine;
import dev.ngb.loyalty.program.api.promotion.PromotionView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Everything an order's earn is decided by at the moment it is recorded: the program's rate and the rules of the day
 * around one order.
 *
 * <p>The promotions handed in are the active ones whose window contains {@code occurredAt}, already stamped with the
 * program's time zone, so this record carries no clock and no zone of its own: the same context always earns the same
 * points, which is what the vectors pin down.
 */
public record EarnContext(
        long unitAmount,
        String currency,
        String tierCode,
        BigDecimal tierMultiplier,
        Instant occurredAt,
        String storeCode,
        String channel,
        List<OrderLine> lines,
        List<PromotionView> promotions) {

    public EarnContext {
        if (unitAmount < 1) {
            throw new IllegalArgumentException("unitAmount must be at least 1");
        }
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(tierMultiplier, "tierMultiplier");
        if (tierMultiplier.signum() < 0) {
            throw new IllegalArgumentException("a tier multiplier must not be negative");
        }
        if (tierMultiplier.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("a tier multiplier has at most two fraction digits");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        lines = lines == null ? List.of() : List.copyOf(lines);
        promotions = promotions == null ? List.of() : List.copyOf(promotions);
    }
}
