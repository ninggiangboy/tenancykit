package dev.ngb.booking.booking.application.booking.command;

import dev.ngb.booking.booking.application.booking.exception.BookingStateException;
import dev.ngb.booking.booking.application.booking.port.BookingRepository;
import dev.ngb.booking.booking.application.booking.service.BookingEventPublisher;
import dev.ngb.booking.booking.application.booking.service.InventoryEngines;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingEvents;
import dev.ngb.booking.booking.domain.booking.BookingStatus;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.UUID;

/**
 * Gives a place or a range back and releases its claims.
 *
 * <p>The booking is read under its row lock, so a cancellation and a confirmation cannot both report a change. A hold
 * or a confirmed booking is cancellable; one that already expired, was cancelled or was marked a no-show is refused,
 * because there is nothing left to give back. Booking never charges a cancellation fee, so the policy code is the
 * plain give-back code.
 */
public class CancelBookingUseCase {

    private final UnitOfWork unitOfWork;
    private final BookingRepository bookings;
    private final InventoryEngines engines;
    private final BookingEventPublisher events;
    private final Clock clock;

    public CancelBookingUseCase(
            UnitOfWork unitOfWork,
            BookingRepository bookings,
            InventoryEngines engines,
            BookingEventPublisher events,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.bookings = bookings;
        this.engines = engines;
        this.events = events;
        this.clock = clock;
    }

    public Booking execute(UUID bookingId) {
        return unitOfWork.transactional(() -> {
            Booking booking = bookings
                    .lockById(bookingId)
                    .orElseThrow(() -> new NotFoundException("No booking exists here with that id."));
            if (booking.status() != BookingStatus.HELD && booking.status() != BookingStatus.CONFIRMED) {
                throw new BookingStateException(
                        "The booking is " + booking.status() + " and cannot be cancelled.");
            }
            Instant now = clock.now();
            engines.of(booking.offeringKind()).release(booking.id().value());
            booking.cancel(now, BookingEvents.NO_POLICY);
            bookings.update(booking);
            events.cancelled(booking, now);
            return booking;
        });
    }
}
