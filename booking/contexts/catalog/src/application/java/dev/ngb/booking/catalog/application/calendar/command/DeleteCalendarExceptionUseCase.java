package dev.ngb.booking.catalog.application.calendar.command;

import dev.ngb.booking.catalog.application.calendar.port.CalendarRepository;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** {@code DELETE /resources/{resourceId}/exceptions/{exceptionId}}: removes one dated exception. */
public class DeleteCalendarExceptionUseCase {

    private final UnitOfWork unitOfWork;
    private final CalendarRepository calendar;

    public DeleteCalendarExceptionUseCase(UnitOfWork unitOfWork, CalendarRepository calendar) {
        this.unitOfWork = unitOfWork;
        this.calendar = calendar;
    }

    /** @throws NotFoundException when no exception with this id belongs to this resource */
    public void execute(UUID resourceId, UUID exceptionId) {
        unitOfWork.transactional(() -> {
            if (!calendar.deleteException(resourceId, exceptionId)) {
                throw new NotFoundException("No exception exists here with that id.");
            }
        });
    }
}
