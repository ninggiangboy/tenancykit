package dev.ngb.booking.inventory.domain.capacity;

import java.util.Objects;
import java.util.UUID;

/** One place's opaque id, so a claim can name the row it took. */
public record CapacityUnitId(UUID value) {

    public CapacityUnitId {
        Objects.requireNonNull(value, "value");
    }

    public static CapacityUnitId of(String text) {
        return new CapacityUnitId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
