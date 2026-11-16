package dev.ngb.loyalty.points.domain.earn;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The rules an order's earn used, stored with the order when it was recorded.
 *
 * <p>It is the single input of a recompute: a return divides by the {@code unitAmount} stored here, never by the
 * program's current one, and applies the multiplier of each line as it was matched, never the promotions of today. That
 * is what makes an order's points reproducible after the program, the tier list or the promotion catalog changed.
 */
public record EarnSnapshot(
        long unitAmount, String currency, String tierCode, BigDecimal tierMultiplier, List<SnapshotLine> lines) {

    public EarnSnapshot {
        if (unitAmount < 1) {
            throw new IllegalArgumentException("unitAmount must be at least 1");
        }
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(tierMultiplier, "tierMultiplier");
        if (tierMultiplier.signum() < 0) {
            throw new IllegalArgumentException("a tier multiplier must not be negative");
        }
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** The multiplier this snapshot recorded for a line, or the tier multiplier when the line was never matched. */
    public BigDecimal multiplierOf(int lineNo) {
        return lines.stream()
                .filter(line -> line.lineNo() == lineNo)
                .map(SnapshotLine::multiplier)
                .findFirst()
                .orElse(tierMultiplier);
    }
}
