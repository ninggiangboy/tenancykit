package dev.ngb.booking.inventory.application.availability.port;

import java.time.Instant;
import java.util.Objects;

/** A range on a resource that is already claimed, so availability subtracts it. */
public record BusyRange(Instant start, Instant end) {

    public BusyRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("a busy range's start is before its end");
        }
    }
}
