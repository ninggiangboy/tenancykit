package dev.ngb.booking.booking.domain.booking;

import java.util.Objects;
import java.util.UUID;

/**
 * One resource a booking claimed, with the offering requirement it fills.
 *
 * <p>The slot code is the offering's own name for the requirement, so a reader knows which of several resources the
 * booking took for what.
 */
public record AssignedResource(String slotCode, UUID resourceId) {

    public AssignedResource {
        Objects.requireNonNull(slotCode, "slotCode");
        Objects.requireNonNull(resourceId, "resourceId");
    }
}
