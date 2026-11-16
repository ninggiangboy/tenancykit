package dev.ngb.crm.orders.domain.returns;

import java.util.Objects;
import java.util.UUID;

/** crm's own opaque id for a return; the Tenant's reference is the natural key and never the id. */
public record ReturnId(UUID value) {

    public ReturnId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
