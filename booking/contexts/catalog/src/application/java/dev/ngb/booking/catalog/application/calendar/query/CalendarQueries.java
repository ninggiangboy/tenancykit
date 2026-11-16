package dev.ngb.booking.catalog.application.calendar.query;

import dev.ngb.booking.catalog.application.resource.query.ResourceReadRepository;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/**
 * Reads a resource's calendar for the GET handlers. A calendar belongs to a resource, so a resource this tenant does
 * not hold is a {@code 404} rather than an empty pattern.
 */
public class CalendarQueries {

    private final UnitOfWork unitOfWork;
    private final CalendarReadRepository calendar;
    private final ResourceReadRepository resources;

    public CalendarQueries(
            UnitOfWork unitOfWork, CalendarReadRepository calendar, ResourceReadRepository resources) {
        this.unitOfWork = unitOfWork;
        this.calendar = calendar;
        this.resources = resources;
    }

    /** @throws NotFoundException when the resource does not exist here */
    public List<WorkingHourView> hours(UUID resourceId) {
        return unitOfWork.transactional(() -> {
            requireResource(resourceId);
            return calendar.hours(resourceId);
        });
    }

    /** @throws NotFoundException when the resource does not exist here */
    public List<CalendarExceptionView> exceptions(UUID resourceId) {
        return unitOfWork.transactional(() -> {
            requireResource(resourceId);
            return calendar.exceptions(resourceId);
        });
    }

    private void requireResource(UUID resourceId) {
        if (resources.findById(resourceId).isEmpty()) {
            throw new NotFoundException("No resource exists here with that id.");
        }
    }
}
