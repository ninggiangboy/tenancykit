package dev.ngb.booking.catalog.api.calendar;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A resource's zone, weekly wall-clock windows and dated exceptions, as availability reads them. */
public record ResourceCalendar(
        UUID resourceId,
        String zone,
        List<WorkingHourWindow> hours,
        List<CalendarExceptionRange> exceptions) {

    public ResourceCalendar {
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(zone, "zone");
        hours = List.copyOf(hours);
        exceptions = List.copyOf(exceptions);
    }
}
