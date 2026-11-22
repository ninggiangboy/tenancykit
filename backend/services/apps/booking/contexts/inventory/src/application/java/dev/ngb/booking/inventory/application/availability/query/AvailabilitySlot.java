package dev.ngb.booking.inventory.application.availability.query;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One free start: the service range and the resource chosen for each requirement, in requirement order. */
public record AvailabilitySlot(Instant start, Instant end, List<UUID> resourceIds) {

    public AvailabilitySlot {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        resourceIds = List.copyOf(resourceIds);
    }
}
