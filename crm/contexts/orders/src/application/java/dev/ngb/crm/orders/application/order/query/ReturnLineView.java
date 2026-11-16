package dev.ngb.crm.orders.application.order.query;

import java.math.BigDecimal;

/** One line of one return: how much of an order line that return took back, not a cumulative figure. */
public record ReturnLineView(int lineNo, BigDecimal quantity, long amount) {}
