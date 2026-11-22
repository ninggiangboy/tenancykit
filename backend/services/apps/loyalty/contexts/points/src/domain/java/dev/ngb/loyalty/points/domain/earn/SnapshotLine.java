package dev.ngb.loyalty.points.domain.earn;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of an earn record: the multiplier the rules of the day gave it and the promotions that decided it.
 *
 * <p>The multiplier is the whole multiplier of the line, the best matching promotion times the member's tier
 * multiplier, so a recompute needs nothing but this number. The promotion ids are kept so a statement can say why a
 * line was worth what it was, long after a promotion was archived.
 */
public record SnapshotLine(int lineNo, BigDecimal multiplier, List<UUID> promotionIds) {

    public SnapshotLine {
        if (lineNo < 1) {
            throw new IllegalArgumentException("a line number starts at 1");
        }
        Objects.requireNonNull(multiplier, "multiplier");
        if (multiplier.signum() < 0) {
            throw new IllegalArgumentException("a multiplier must not be negative");
        }
        promotionIds = promotionIds == null ? List.of() : List.copyOf(promotionIds);
    }
}
