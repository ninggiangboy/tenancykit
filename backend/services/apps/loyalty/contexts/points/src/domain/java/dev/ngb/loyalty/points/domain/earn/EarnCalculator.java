package dev.ngb.loyalty.points.domain.earn;

import dev.ngb.loyalty.points.domain.order.OrderLine;
import dev.ngb.loyalty.program.api.promotion.PromotionMatch;
import dev.ngb.loyalty.program.api.promotion.PromotionView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * How many points a set of order lines is worth, and nothing else: no clock, no repository, no framework.
 *
 * <p>For every line whose {@code earnEligible} is not false, the line multiplier is the multiplier of the best matching
 * promotion times the member's tier multiplier, and 1 when no promotion matches. The points are the sum of each line's
 * eligible amount times its multiplier, divided once by the program's {@code unitAmount} and rounded to a whole number
 * with {@code HALF_EVEN} — one rounding, at the very end, so 2.5 becomes 2 and 3.5 becomes 4.
 *
 * <p>The best matching promotion is the one with the highest multiplier; equal multipliers are broken by the higher
 * priority and then by the smaller id, so the answer never depends on the order the rules arrive in.
 *
 * <p>A return is never computed with this method: it recomputes with {@link #recompute(EarnSnapshot, List)}, whose only
 * input is the snapshot stored when the order was earned.
 */
public final class EarnCalculator {

    /** What a line multiplies by when no promotion matches: the default multiplier of the vector contract. */
    public static final BigDecimal NO_PROMOTION = BigDecimal.ONE;

    /** Judges an order's lines against the rules of the day and records the snapshot they were judged with. */
    public EarnOutcome evaluate(EarnContext context) {
        BigDecimal tierMultiplier = context.tierMultiplier();
        List<SnapshotLine> snapshotLines = new ArrayList<>();
        List<LineContribution> contributions = new ArrayList<>();
        BigDecimal weighted = BigDecimal.ZERO;
        long spend = 0L;
        for (OrderLine line : context.lines()) {
            PromotionView best = bestPromotion(context, line);
            BigDecimal lineMultiplier = (best == null ? NO_PROMOTION : best.multiplier()).multiply(tierMultiplier);
            List<UUID> promotionIds = best == null ? List.of() : List.of(best.promotionId());
            snapshotLines.add(new SnapshotLine(line.lineNo(), lineMultiplier, promotionIds));
            long eligible = eligibleAmount(line);
            weighted = weighted.add(BigDecimal.valueOf(eligible).multiply(lineMultiplier));
            spend += eligible;
            contributions.add(new LineContribution(line.lineNo(), lineMultiplier, eligible, promotionIds));
        }
        EarnSnapshot snapshot = new EarnSnapshot(
                context.unitAmount(), context.currency(), context.tierCode(), tierMultiplier, snapshotLines);
        return new EarnOutcome(points(weighted, context.unitAmount()), spend, snapshot, contributions);
    }

    /**
     * What the lines are worth now that part of them has been returned, computed only from the multipliers the order
     * recorded. A line the snapshot does not name falls back to the snapshot's tier multiplier.
     */
    public EarnOutcome recompute(EarnSnapshot snapshot, List<OrderLine> lines) {
        List<LineContribution> contributions = new ArrayList<>();
        BigDecimal weighted = BigDecimal.ZERO;
        long spend = 0L;
        for (OrderLine line : lines) {
            BigDecimal multiplier = snapshot.multiplierOf(line.lineNo());
            long eligible = eligibleAmount(line);
            weighted = weighted.add(BigDecimal.valueOf(eligible).multiply(multiplier));
            spend += eligible;
            contributions.add(new LineContribution(line.lineNo(), multiplier, eligible, List.of()));
        }
        return new EarnOutcome(points(weighted, snapshot.unitAmount()), spend, snapshot, contributions);
    }

    private static PromotionView bestPromotion(EarnContext context, OrderLine line) {
        PromotionMatch match = new PromotionMatch(
                context.storeCode(), line.category(), line.sku(), context.tierCode(), context.occurredAt());
        PromotionView best = null;
        for (PromotionView promotion : context.promotions()) {
            if (!promotion.active() || !promotion.matches(match)) {
                continue;
            }
            if (best == null || beats(promotion, best)) {
                best = promotion;
            }
        }
        return best;
    }

    private static boolean beats(PromotionView candidate, PromotionView best) {
        int byMultiplier = candidate.multiplier().compareTo(best.multiplier());
        if (byMultiplier != 0) {
            return byMultiplier > 0;
        }
        if (candidate.priority() != best.priority()) {
            return candidate.priority() > best.priority();
        }
        return candidate.promotionId().compareTo(best.promotionId()) < 0;
    }

    /** A line earns on what is left of it, and only when it is eligible at all. */
    private static long eligibleAmount(OrderLine line) {
        return line.earnEligible() ? line.netAmount() : 0L;
    }

    /** The single {@code HALF_EVEN} rounding of the whole computation: the exact quotient, rounded once. */
    private static long points(BigDecimal weighted, long unitAmount) {
        return weighted.divide(BigDecimal.valueOf(unitAmount), 0, RoundingMode.HALF_EVEN)
                .longValueExact();
    }
}
