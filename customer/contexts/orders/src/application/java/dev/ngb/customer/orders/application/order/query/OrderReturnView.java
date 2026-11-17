package dev.ngb.customer.orders.application.order.query;

import java.time.Instant;
import java.util.List;

/** One return recorded against an order, as a read returns it. */
public record OrderReturnView(String returnRef, Instant occurredAt, List<ReturnLineView> lines) {

    public OrderReturnView {
        lines = List.copyOf(lines);
    }
}
