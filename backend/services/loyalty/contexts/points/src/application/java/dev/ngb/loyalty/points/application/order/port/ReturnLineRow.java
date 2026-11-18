package dev.ngb.loyalty.points.application.order.port;

import java.math.BigDecimal;

/** One line of a return as it is stored: which order line came back, how much of it and for how much. */
public record ReturnLineRow(int lineNo, BigDecimal quantity, long amount) {}
