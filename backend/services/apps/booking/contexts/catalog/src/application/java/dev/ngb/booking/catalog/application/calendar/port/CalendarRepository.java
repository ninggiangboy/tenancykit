package dev.ngb.booking.catalog.application.calendar.port;

import dev.ngb.booking.catalog.domain.calendar.CalendarException;
import dev.ngb.booking.catalog.domain.calendar.WorkingHour;
import java.util.List;
import java.util.UUID;

/**
 * How the write side stores a resource's opening pattern and its dated exceptions. Every statement is narrowed to the
 * current tenant by row-level security.
 */
public interface CalendarRepository {

    /** The resource's weekly pattern, ordered by weekday and opening minute. */
    List<WorkingHour> hours(UUID resourceId);

    /** Replaces the whole weekly pattern with {@code hours}, which a caller states in full. */
    void replaceHours(UUID resourceId, List<WorkingHour> hours);

    /** The resource's dated exceptions. */
    List<CalendarException> exceptions(UUID resourceId);

    void addException(CalendarException exception);

    /** @return false when no exception with this id belongs to this resource */
    boolean deleteException(UUID resourceId, UUID exceptionId);
}
