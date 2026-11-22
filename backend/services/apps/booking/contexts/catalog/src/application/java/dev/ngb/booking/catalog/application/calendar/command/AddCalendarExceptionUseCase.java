package dev.ngb.booking.catalog.application.calendar.command;

import dev.ngb.booking.catalog.application.calendar.port.CalendarRepository;
import dev.ngb.booking.catalog.application.calendar.service.CalendarRules;
import dev.ngb.booking.catalog.application.catalog.exception.CatalogValidationException;
import dev.ngb.booking.catalog.application.resource.port.ResourceRepository;
import dev.ngb.booking.catalog.domain.calendar.CalendarException;
import dev.ngb.booking.catalog.domain.calendar.CalendarExceptionId;
import dev.ngb.booking.catalog.domain.calendar.ExceptionKind;
import dev.ngb.booking.catalog.domain.calendar.WorkingHour;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /resources/{resourceId}/exceptions}: the Tenant closes a range of dates or replaces their hours.
 *
 * <p>A {@code CLOSED} exception stores no hours, and an {@code OPEN} one stores the windows that replace the range's
 * pattern, which are the only hours a caller may name here.
 */
public class AddCalendarExceptionUseCase {

    /** One exception as the Tenant sent it; the hours are meaningful when {@code kind} is {@code OPEN}. */
    public record Command(LocalDate fromDate, LocalDate toDate, ExceptionKind kind, List<WorkingHour> hours) {}

    private static final String UNKNOWN_RESOURCE = "No resource exists here with that id.";

    private final UnitOfWork unitOfWork;
    private final ResourceRepository resources;
    private final CalendarRepository calendar;
    private final TenantContext tenantContext;
    private final IdGenerator ids;

    public AddCalendarExceptionUseCase(
            UnitOfWork unitOfWork,
            ResourceRepository resources,
            CalendarRepository calendar,
            TenantContext tenantContext,
            IdGenerator ids) {
        this.unitOfWork = unitOfWork;
        this.resources = resources;
        this.calendar = calendar;
        this.tenantContext = tenantContext;
        this.ids = ids;
    }

    /** @throws NotFoundException when the resource does not exist here */
    public CalendarException execute(UUID resourceId, Command command) {
        LocalDate fromDate = command.fromDate();
        LocalDate toDate = command.toDate();
        ExceptionKind kind = command.kind();
        if (fromDate == null || toDate == null) {
            throw new CatalogValidationException("An exception needs a from and a to date.");
        }
        if (toDate.isBefore(fromDate)) {
            throw new CatalogValidationException("An exception ends before it starts.");
        }
        if (kind == null) {
            throw new CatalogValidationException("A kind is required: CLOSED or OPEN.");
        }
        List<WorkingHour> hours = kind == ExceptionKind.CLOSED
                ? List.of()
                : CalendarRules.weekly(command.hours() == null ? List.of() : command.hours());
        if (kind == ExceptionKind.OPEN && hours.isEmpty()) {
            throw new CatalogValidationException("An OPEN exception carries at least one window.");
        }
        return unitOfWork.transactional(() -> {
            if (resources.findById(resourceId).isEmpty()) {
                throw new NotFoundException(UNKNOWN_RESOURCE);
            }
            CalendarException exception = new CalendarException(
                    new CalendarExceptionId(ids.newId()),
                    tenantContext.requireTenant(),
                    resourceId,
                    fromDate,
                    toDate,
                    kind,
                    hours);
            calendar.addException(exception);
            return exception;
        });
    }
}
