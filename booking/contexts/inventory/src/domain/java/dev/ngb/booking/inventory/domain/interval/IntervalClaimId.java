package dev.ngb.booking.inventory.domain.interval;

import java.util.Objects;
import java.util.UUID;

/** One interval claim's own identity, so a release can be traced to the row it freed. */
public record IntervalClaimId(UUID value) {

    public IntervalClaimId {
        Objects.requireNonNull(value, "value");
    }
}
