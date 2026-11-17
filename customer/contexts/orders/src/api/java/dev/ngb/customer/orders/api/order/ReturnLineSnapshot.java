package dev.ngb.customer.orders.api.order;

import java.math.BigDecimal;

/** One returned line of a return as another context reads it: what came back in that return, not the cumulative state. */
public record ReturnLineSnapshot(int lineNo, BigDecimal quantity, long amount) {}
