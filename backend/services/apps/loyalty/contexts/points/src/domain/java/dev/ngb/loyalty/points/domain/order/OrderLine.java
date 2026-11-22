package dev.ngb.loyalty.points.domain.order;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One order line as earning and returning see it: the line number the Tenant uses, what the line is, how much of it was
 * sold and how much has been returned since.
 *
 * <p>{@code amount} is minor units of the order currency net of discount, so points are a division away from it. A line
 * is immutable once recorded; a return produces a new instance with its returned figures carried forward, which is why
 * the cumulative figures live here rather than in the return.
 */
public record OrderLine(
        int lineNo,
        String sku,
        String category,
        BigDecimal quantity,
        long amount,
        boolean earnEligible,
        BigDecimal returnedQuantity,
        long returnedAmount) {

    public OrderLine {
        if (lineNo < 1) {
            throw new IllegalArgumentException("a line number starts at 1");
        }
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("a quantity must be positive");
        }
        if (quantity.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("a quantity has at most three fraction digits");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("an amount must not be negative");
        }
        returnedQuantity = returnedQuantity == null ? BigDecimal.ZERO : returnedQuantity;
        if (returnedQuantity.signum() < 0) {
            throw new IllegalArgumentException("a returned quantity must not be negative");
        }
        if (returnedQuantity.compareTo(quantity) > 0) {
            throw new IllegalArgumentException("a returned quantity never exceeds what was sold");
        }
        if (returnedAmount < 0) {
            throw new IllegalArgumentException("a returned amount must not be negative");
        }
        if (returnedAmount > amount) {
            throw new IllegalArgumentException("a returned amount never exceeds what was sold");
        }
    }

    /** A line as an order first records it: nothing returned yet. */
    public static OrderLine sold(
            int lineNo, String sku, String category, BigDecimal quantity, long amount, boolean earnEligible) {
        return new OrderLine(lineNo, sku, category, quantity, amount, earnEligible, BigDecimal.ZERO, 0L);
    }

    /** The same line with one more return applied to it. */
    public OrderLine returned(BigDecimal moreQuantity, long moreAmount) {
        Objects.requireNonNull(moreQuantity, "moreQuantity");
        if (moreQuantity.signum() <= 0) {
            throw new IllegalArgumentException("a returned quantity must be positive");
        }
        if (moreAmount < 0) {
            throw new IllegalArgumentException("a returned amount must not be negative");
        }
        return new OrderLine(
                lineNo,
                sku,
                category,
                quantity,
                amount,
                earnEligible,
                returnedQuantity.add(moreQuantity),
                returnedAmount + moreAmount);
    }

    /** The part of the line still worth points: what was sold minus what has come back. */
    public long netAmount() {
        return amount - returnedAmount;
    }
}
