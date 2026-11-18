package dev.ngb.booking.catalog.domain.group;

import java.util.Objects;
import java.util.UUID;

/** catalog's own id for a resource group; the Tenant's code is the natural key and never the id. */
public record GroupId(UUID value) {

    public GroupId {
        Objects.requireNonNull(value, "value");
    }

    public static GroupId of(String text) {
        return new GroupId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
