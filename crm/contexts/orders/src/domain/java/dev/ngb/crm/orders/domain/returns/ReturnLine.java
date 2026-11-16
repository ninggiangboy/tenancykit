package dev.ngb.crm.orders.domain.returns;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One line of a return: how much of an order line came back, and how much money with it.
 *
 * <p>It is what the Tenant sent, not a cumulative figure: the order line holds the running total, so the same return
 * read back twice says the same thing.
 */
public record ReturnLine(int lineNo, BigDecimal quantity, long amount) {

    public ReturnLine {
        Objects.requireNonNull(quantity, "quantity");
    }
}
