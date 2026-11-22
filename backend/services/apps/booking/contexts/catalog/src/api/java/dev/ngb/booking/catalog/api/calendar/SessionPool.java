package dev.ngb.booking.catalog.api.calendar;

import java.util.Objects;

/** One pool of a session: its code and its capacity. */
public record SessionPool(String code, int capacity) {

    public SessionPool {
        Objects.requireNonNull(code, "code");
        if (capacity < 0) {
            throw new IllegalArgumentException("a pool's capacity is never negative");
        }
    }
}
