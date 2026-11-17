package dev.ngb.booking.catalog.api.calendar;

import java.time.LocalDate;
import java.util.List;

/** A dated exception: {@code CLOSED} closes the range, {@code OPEN} replaces those days with the given windows. */
public record CalendarExceptionRange(
        LocalDate fromDate, LocalDate toDate, String kind, List<WorkingHourWindow> hours) {

    public CalendarExceptionRange {
        hours = List.copyOf(hours);
    }
}
