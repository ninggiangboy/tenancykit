package dev.ngb.crm.orders.api.order;

import java.math.BigDecimal;

/**
 * One line of an order as another context reads it: what was sold and how much of it has come back.
 *
 * @param lineNo the line's number within its order, starting at 1
 * @param sku the item's identifier in the Tenant's own catalogue
 * @param name the item's name, or null
 * @param category the category the item was filed under
 * @param quantity how many were sold, to three fraction digits
 * @param amount the line's net amount in minor units of the order's currency
 * @param earnEligible whether the line is part of what the Tenant may reward
 * @param returnedQuantity how many of them came back, cumulatively
 * @param returnedAmount how much of the line came back, cumulatively
 */
public record OrderLineSnapshot(
        int lineNo,
        String sku,
        String name,
        String category,
        BigDecimal quantity,
        long amount,
        boolean earnEligible,
        BigDecimal returnedQuantity,
        long returnedAmount) {}
