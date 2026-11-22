package dev.ngb.booking.catalog.domain.resource;

import java.util.Objects;
import java.util.UUID;

/** A resource's opaque id; the Tenant's code is the natural key and never the id. */
public record ResourceId(UUID value) {

    public ResourceId {
        Objects.requireNonNull(value, "value");
    }

    public static ResourceId of(String text) {
        return new ResourceId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
