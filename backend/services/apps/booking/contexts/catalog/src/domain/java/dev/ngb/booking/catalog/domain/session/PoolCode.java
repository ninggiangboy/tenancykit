package dev.ngb.booking.catalog.domain.session;

import java.util.Objects;

/** A pool's code: the Tenant's own name for a group of places, at most 60 characters. */
public record PoolCode(String value) {

    public static final String DEFAULT = "default";

    public PoolCode {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.length() > 60) {
            throw new IllegalArgumentException("a pool code is between 1 and 60 characters");
        }
    }

    /** The pool a series carries when the caller named none. */
    public static PoolCode defaultPool() {
        return new PoolCode(DEFAULT);
    }

    @Override
    public String toString() {
        return value;
    }
}
