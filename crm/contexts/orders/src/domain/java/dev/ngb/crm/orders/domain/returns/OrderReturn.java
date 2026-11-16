package dev.ngb.crm.orders.domain.returns;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One return recorded against an order.
 *
 * <p>{@code version} is the order version this return produced, so the returns of an order are read back in the order
 * they were recorded and a receiver can tell which of two {@code order.refunded} deliveries is newer. The lines are
 * what this return took back, never the cumulative state of the order — that lives on the order's own lines.
 */
public record OrderReturn(ReturnId id, String returnRef, Instant occurredAt, long version, List<ReturnLine> lines) {

    public OrderReturn {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(returnRef, "returnRef");
        Objects.requireNonNull(occurredAt, "occurredAt");
        lines = List.copyOf(lines);
    }
}
