package dev.ngb.booking.catalog.application.calendar.query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One dated exception as a read returns it. The kind is the stored code because a read model carries primitives, and
 * the hours are the windows an {@code OPEN} exception replaces the range with.
 */
public record CalendarExceptionView(
        UUID id,
        LocalDate fromDate,
        LocalDate toDate,
        String kind,
        List<WorkingHourView> hours) {}
