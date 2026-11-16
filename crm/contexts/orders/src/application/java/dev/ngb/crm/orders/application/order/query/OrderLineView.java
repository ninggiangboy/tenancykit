package dev.ngb.crm.orders.application.order.query;

import java.math.BigDecimal;

/**
 * One line of an order as a read returns it: what was sold, and how much of it came back across every return.
 *
 * <p>{@code earnEligible} and {@code amount} are the two fields a consumer of an order webhook reads to work out points,
 * so they are reported exactly as stored.
 */
public record OrderLineView(
        int lineNo,
        String sku,
        String name,
        String category,
        BigDecimal quantity,
        long amount,
        boolean earnEligible,
        BigDecimal returnedQuantity,
        long returnedAmount) {}
