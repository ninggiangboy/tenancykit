package dev.ngb.booking.catalog.api.calendar;

import java.time.Instant;
import java.util.Objects;

/** One closed range on a resource; availability subtracts it from the open time. */
public record BlockRange(Instant startsAt, Instant endsAt) {

    public BlockRange {
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("a block's start is before its end");
        }
    }
}
