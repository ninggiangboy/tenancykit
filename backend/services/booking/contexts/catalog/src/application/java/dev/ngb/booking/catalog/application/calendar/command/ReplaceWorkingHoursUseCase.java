package dev.ngb.booking.catalog.application.calendar.command;

import dev.ngb.booking.catalog.application.calendar.port.CalendarRepository;
import dev.ngb.booking.catalog.application.calendar.service.CalendarRules;
import dev.ngb.booking.catalog.application.resource.port.ResourceRepository;
import dev.ngb.booking.catalog.domain.calendar.WorkingHour;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/**
 * {@code PUT /resources/{resourceId}/working-hours}: the caller states the whole weekly pattern, so a write replaces it
 * rather than merging into it. An empty pattern means the resource is never open.
 */
public class ReplaceWorkingHoursUseCase {

    /** The whole pattern, in local wall-clock minutes, already parsed from the request's times. */
    public record Command(List<WorkingHour> hours) {}

    private static final String UNKNOWN_RESOURCE = "No resource exists here with that id.";

    private final UnitOfWork unitOfWork;
    private final ResourceRepository resources;
    private final CalendarRepository calendar;

    public ReplaceWorkingHoursUseCase(
            UnitOfWork unitOfWork, ResourceRepository resources, CalendarRepository calendar) {
        this.unitOfWork = unitOfWork;
        this.resources = resources;
        this.calendar = calendar;
    }

    /** @throws NotFoundException when the resource does not exist here */
    public List<WorkingHour> execute(UUID resourceId, Command command) {
        List<WorkingHour> hours = CalendarRules.weekly(command.hours() == null ? List.of() : command.hours());
        return unitOfWork.transactional(() -> {
            if (resources.findById(resourceId).isEmpty()) {
                throw new NotFoundException(UNKNOWN_RESOURCE);
            }
            calendar.replaceHours(resourceId, hours);
            return hours;
        });
    }
}
