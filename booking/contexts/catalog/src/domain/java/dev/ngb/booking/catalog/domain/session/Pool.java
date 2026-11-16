package dev.ngb.booking.catalog.domain.session;

import java.util.Objects;

/**
 * One group of places in a session, such as an adult or a child pool.
 *
 * <p>The capacity is how many places the pool offers; how many of them are free is a read of the places themselves and
 * is never stored here.
 */
public record Pool(PoolCode code, int capacity) {

    public Pool {
        Objects.requireNonNull(code, "code");
        if (capacity < 0) {
            throw new IllegalArgumentException("a pool's capacity is at least 0");
        }
    }
}
