package dev.ngb.booking.booking.application.booking.command;

import dev.ngb.booking.booking.application.booking.exception.BookingStateException;
import dev.ngb.booking.booking.application.booking.exception.HoldLapsedException;
import dev.ngb.booking.booking.application.booking.port.BookingRepository;
import dev.ngb.booking.booking.application.booking.service.BookingEventPublisher;
import dev.ngb.booking.booking.application.booking.service.InventoryEngines;
import dev.ngb.booking.booking.application.waitlist.service.WaitlistFollowUp;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingStatus;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.UUID;

/**
 * Makes a held booking's claims permanent, once.
 *
 * <p>The booking is read under its row lock, so two confirmations that arrive together decide in order rather than both
 * reporting a change: the first confirms it and the second finds it already confirmed and returns the same booking. A
 * hold the worker already expired, or one whose grace has run out, is refused rather than confirmed on a claim that is
 * no longer held.
 */
public class ConfirmBookingUseCase {

    private final UnitOfWork unitOfWork;
    private final BookingRepository bookings;
    private final InventoryEngines engines;
    private final BookingEventPublisher events;
    private final WaitlistFollowUp waitlist;
    private final Clock clock;
    private final long graceSeconds;

    public ConfirmBookingUseCase(
            UnitOfWork unitOfWork,
            BookingRepository bookings,
            InventoryEngines engines,
            BookingEventPublisher events,
            WaitlistFollowUp waitlist,
            Clock clock,
            long graceSeconds) {
        this.unitOfWork = unitOfWork;
        this.bookings = bookings;
        this.engines = engines;
        this.events = events;
        this.waitlist = waitlist;
        this.clock = clock;
        this.graceSeconds = graceSeconds;
    }

    public Booking execute(UUID bookingId) {
        return unitOfWork.transactional(() -> {
            Booking booking = bookings
                    .lockById(bookingId)
                    .orElseThrow(() -> new NotFoundException("No booking exists here with that id."));
            if (booking.status() == BookingStatus.CONFIRMED) {
                return booking;
            }
            Instant now = clock.now();
            if (booking.status() != BookingStatus.HELD) {
                if (booking.status() == BookingStatus.EXPIRED || booking.lapsedAt(now, graceSeconds)) {
                    throw new HoldLapsedException("The hold is gone.");
                }
                throw new BookingStateException(
                        "The booking is " + booking.status() + " and cannot be confirmed.");
            }
            engines.of(booking.offeringKind()).confirm(booking.id().value());
            booking.confirm(now);
            bookings.update(booking);
            events.confirmed(booking, now);
            waitlist.accept(booking.id().value());
            return booking;
        });
    }
}
