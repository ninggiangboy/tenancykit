package dev.ngb.booking.booking.application.waitlist.command;

import dev.ngb.booking.booking.application.booking.exception.BookingStateException;
import dev.ngb.booking.booking.application.waitlist.port.WaitlistRepository;
import dev.ngb.booking.booking.domain.waitlist.WaitlistEntry;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** Takes a waiting entry out of line; an offered entry cannot leave, because its hold must be released instead. */
public class LeaveWaitlistUseCase {

    private final UnitOfWork unitOfWork;
    private final WaitlistRepository entries;
    private final Clock clock;

    public LeaveWaitlistUseCase(UnitOfWork unitOfWork, WaitlistRepository entries, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.entries = entries;
        this.clock = clock;
    }

    public void execute(UUID entryId) {
        unitOfWork.transactional(() -> {
            WaitlistEntry entry = entries
                    .lockById(entryId)
                    .orElseThrow(() -> new NotFoundException("No waitlist entry exists here with that id."));
            if (!entry.cancel(clock.now())) {
                throw new BookingStateException(
                        "The entry is " + entry.status() + "; an offered hold is released, not left.");
            }
            entries.update(entry);
            return null;
        });
    }
}
