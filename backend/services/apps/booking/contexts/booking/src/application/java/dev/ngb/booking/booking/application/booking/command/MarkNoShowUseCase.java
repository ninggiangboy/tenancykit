package dev.ngb.booking.booking.application.booking.command;

import dev.ngb.booking.booking.application.booking.exception.BookingStateException;
import dev.ngb.booking.booking.application.booking.port.BookingRepository;
import dev.ngb.booking.booking.application.booking.service.BookingEventPublisher;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.UUID;

/** Marks a confirmed booking whose start has passed as a no-show; its claims stay as history. */
public class MarkNoShowUseCase {

    private final UnitOfWork unitOfWork;
    private final BookingRepository bookings;
    private final BookingEventPublisher events;
    private final Clock clock;

    public MarkNoShowUseCase(
            UnitOfWork unitOfWork, BookingRepository bookings, BookingEventPublisher events, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.bookings = bookings;
        this.events = events;
        this.clock = clock;
    }

    public Booking execute(UUID bookingId) {
        return unitOfWork.transactional(() -> {
            Booking booking = bookings
                    .lockById(bookingId)
                    .orElseThrow(() -> new NotFoundException("No booking exists here with that id."));
            Instant now = clock.now();
            if (!booking.markNoShow(now)) {
                throw new BookingStateException("Only a confirmed booking whose start has passed can be a no-show");
            }
            bookings.update(booking);
            events.noShow(booking, now);
            return booking;
        });
    }
}
