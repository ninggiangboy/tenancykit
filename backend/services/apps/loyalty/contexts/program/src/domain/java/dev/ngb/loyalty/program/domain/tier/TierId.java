package dev.ngb.loyalty.program.domain.tier;

import java.util.Objects;
import java.util.UUID;

/** A tier's own id, which survives a list replace as long as its code does. */
public record TierId(UUID value) {

    public TierId {
        Objects.requireNonNull(value, "value");
    }

    public static TierId of(String text) {
        return new TierId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
