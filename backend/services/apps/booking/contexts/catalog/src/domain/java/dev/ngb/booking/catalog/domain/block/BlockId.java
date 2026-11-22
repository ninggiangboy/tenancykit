package dev.ngb.booking.catalog.domain.block;

import java.util.Objects;
import java.util.UUID;

/** A block's opaque id; a Tenant never names one. */
public record BlockId(UUID value) {

    public BlockId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
