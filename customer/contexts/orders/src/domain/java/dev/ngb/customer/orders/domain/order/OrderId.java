package dev.ngb.customer.orders.domain.order;

import java.util.Objects;
import java.util.UUID;

/** customer's own opaque id for an order; the Tenant's reference is the natural key and never the id. */
public record OrderId(UUID value) {

    public OrderId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
