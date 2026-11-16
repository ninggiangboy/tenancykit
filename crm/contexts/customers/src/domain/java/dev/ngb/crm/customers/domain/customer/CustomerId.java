package dev.ngb.crm.customers.domain.customer;

import java.util.Objects;
import java.util.UUID;

/** crm's own opaque id for a customer; the Tenant's reference is the natural key and never the id. */
public record CustomerId(UUID value) {

    public CustomerId {
        Objects.requireNonNull(value, "value");
    }

    public static CustomerId of(String text) {
        return new CustomerId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
