package dev.ngb.booking.catalog.application.calendar.query;

import java.util.List;
import java.util.UUID;

/** Reads a resource's opening pattern and its dated exceptions straight into the views. */
public interface CalendarReadRepository {

    /** The resource's weekly pattern, ordered by weekday and opening minute. */
    List<WorkingHourView> hours(UUID resourceId);

    /** The resource's dated exceptions. */
    List<CalendarExceptionView> exceptions(UUID resourceId);
}
