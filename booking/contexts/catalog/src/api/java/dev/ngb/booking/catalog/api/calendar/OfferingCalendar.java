package dev.ngb.booking.catalog.api.calendar;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One offering's rules as availability needs them: the duration grid, the slot grid, the buffers, the lead time, the
 * horizon and the requirements a booking must satisfy.
 */
public record OfferingCalendar(
        UUID offeringId,
        String kind,
        int durationMin,
        Integer durationMax,
        int durationStep,
        int slotStep,
        int bufferBefore,
        int bufferAfter,
        int leadTimeSeconds,
        int horizonDays,
        Integer holdTtlSeconds,
        int maxQty,
        List<OfferingRequirement> requirements) {

    public OfferingCalendar {
        Objects.requireNonNull(offeringId, "offeringId");
        Objects.requireNonNull(kind, "kind");
        requirements = List.copyOf(requirements);
    }
}
