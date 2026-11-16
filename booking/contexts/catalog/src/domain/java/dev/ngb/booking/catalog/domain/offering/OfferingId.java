package dev.ngb.booking.catalog.domain.offering;

import java.util.Objects;
import java.util.UUID;

/** catalog's own id for an offering; the Tenant's code is the natural key and never the id. */
public record OfferingId(UUID value) {

    public OfferingId {
        Objects.requireNonNull(value, "value");
    }

    public static OfferingId of(String text) {
        return new OfferingId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
