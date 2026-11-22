package dev.ngb.customer.orders.api.order;

import java.time.Instant;
import java.util.List;

/** One return recorded against an order, as another context reads it. */
public record ReturnSnapshot(String returnRef, Instant occurredAt, List<ReturnLineSnapshot> lines) {

    public ReturnSnapshot {
        lines = List.copyOf(lines);
    }
}
