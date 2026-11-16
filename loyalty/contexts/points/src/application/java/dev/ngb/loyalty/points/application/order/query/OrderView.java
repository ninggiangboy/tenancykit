package dev.ngb.loyalty.points.application.order.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What an order is worth now, the snapshot its earn used and what each return took back.
 *
 * <p>{@code pointsGranted} is the order's points after its returns, {@code available} and {@code debt} are the member's
 * figures at the moment of the read, and the snapshot is shown so a caller can see which promotions and which tier
 * multiplier decided the points.
 */
public record OrderView(
        String orderRef,
        String externalRef,
        String currency,
        Instant occurredAt,
        String storeCode,
        String channel,
        long pointsGranted,
        long spendAmount,
        long available,
        long debt,
        EarnSnapshotView earnSnapshot,
        List<LineView> lines,
        List<ReturnView> returns) {

    public record EarnSnapshotView(
            long unitAmount, String currency, String tierCode, BigDecimal tierMultiplier, List<SnapshotLineView> lines) {}

    public record SnapshotLineView(int lineNo, BigDecimal multiplier, List<String> promotionIds) {}

    public record LineView(
            int lineNo,
            String sku,
            String category,
            BigDecimal quantity,
            long amount,
            boolean earnEligible,
            BigDecimal returnedQuantity,
            long returnedAmount) {}

    public record ReturnView(String returnRef, Instant occurredAt, long pointsClawedBack, long debtAdded) {}
}
