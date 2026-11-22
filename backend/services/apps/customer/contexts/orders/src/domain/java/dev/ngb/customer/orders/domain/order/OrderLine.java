package dev.ngb.customer.orders.domain.order;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One sold item of an order, with the figures every return moves.
 *
 * <p>{@code quantity} and {@code amount} are what was sold; {@code returnedQuantity} and {@code returnedAmount} are the
 * cumulative figures across every return of the order and never exceed what was sold. The amount is net of discount
 * and in minor units of the order's currency, and {@code earnEligible} is what a consumer of the order webhook reads
 * to decide whether the line counts towards points.
 */
public record OrderLine(
        int lineNo,
        String sku,
        String name,
        String category,
        BigDecimal quantity,
        long amount,
        boolean earnEligible,
        BigDecimal returnedQuantity,
        long returnedAmount) {

    public OrderLine {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(returnedQuantity, "returnedQuantity");
    }

    /** A line as the Tenant sold it: nothing has come back yet. */
    public static OrderLine sold(
            int lineNo,
            String sku,
            String name,
            String category,
            BigDecimal quantity,
            long amount,
            boolean earnEligible) {
        return new OrderLine(lineNo, sku, name, category, quantity, amount, earnEligible, BigDecimal.ZERO, 0L);
    }

    /** The same line with what this return takes back added to its cumulative figures. */
    public OrderLine returned(BigDecimal quantityBack, long amountBack) {
        return new OrderLine(
                lineNo,
                sku,
                name,
                category,
                quantity,
                amount,
                earnEligible,
                returnedQuantity.add(quantityBack),
                returnedAmount + amountBack);
    }

    /** @return whether this line is back in full, which is what makes an order {@code RETURNED} rather than partial */
    public boolean fullyReturned() {
        return returnedQuantity.compareTo(quantity) >= 0;
    }

    /** @return whether anything of this line came back at all */
    public boolean partiallyReturned() {
        return returnedQuantity.signum() > 0;
    }
}
