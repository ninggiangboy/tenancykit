package dev.ngb.booking.catalog.domain.calendar;

import dev.ngb.core.id.TenantId;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A dated exception to a resource's weekly pattern: a range of dates a Tenant closes, or the replacement hours it opens
 * them with.
 *
 * <p>The dates are local to the resource's zone. A {@code CLOSED} exception carries no hours and an {@code OPEN} one
 * carries the windows that replace that range's pattern; both are stored so a later read explains why a day differs.
 */
public record CalendarException(
        CalendarExceptionId id,
        TenantId tenantId,
        UUID resourceId,
        LocalDate fromDate,
        LocalDate toDate,
        ExceptionKind kind,
        List<WorkingHour> hours) {

    public CalendarException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(fromDate, "fromDate");
        Objects.requireNonNull(toDate, "toDate");
        Objects.requireNonNull(kind, "kind");
        hours = hours == null ? List.of() : List.copyOf(hours);
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("an exception ends before it starts: " + fromDate + " to " + toDate);
        }
    }
}
