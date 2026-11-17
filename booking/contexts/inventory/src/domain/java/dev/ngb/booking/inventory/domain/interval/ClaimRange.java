package dev.ngb.booking.inventory.domain.interval;

import java.time.Instant;
import java.util.Objects;

/** A half-open range {@code [start, end)} on one resource; its buffers are already folded in. */
public record ClaimRange(Instant start, Instant end) {

    public ClaimRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("a claim's start is before its end");
        }
    }
}
