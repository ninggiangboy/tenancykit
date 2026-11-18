package dev.ngb.booking.booking.application.booking.command;

import dev.ngb.booking.booking.application.booking.exception.BookingStateException;
import dev.ngb.booking.booking.application.booking.port.BookingRepository;
import dev.ngb.booking.booking.application.booking.service.BookingEventPublisher;
import dev.ngb.booking.booking.application.booking.service.InventoryEngines;
import dev.ngb.booking.booking.application.waitlist.service.WaitlistFollowUp;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingEvents;
import dev.ngb.booking.booking.domain.booking.BookingStatus;
import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.catalog.api.calendar.CancellationWindow;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Gives a place or a range back and releases its claims.
 *
 * <p>The booking is read under its row lock, so a cancellation and a confirmation cannot both report a change. A hold
 * or a confirmed booking is cancellable; one that already expired, was cancelled or was marked a no-show is refused,
 * because there is nothing left to give back.
 *
 * <p>A confirmed booking reports which of the offering's cancellation windows it fell in, by the notice it gave: the
 * longest window whose threshold the remaining time still meets. Booking never charges, so the code is all a Tenant
 * gets, and an offering with no windows answers {@code NONE}, while a cancellation after the start answers
 * {@code AFTER_START}.
 */
public class CancelBookingUseCase {

    private static final String AFTER_START = "AFTER_START";

    private final UnitOfWork unitOfWork;
    private final BookingRepository bookings;
    private final InventoryEngines engines;
    private final CalendarReader calendars;
    private final BookingEventPublisher events;
    private final WaitlistFollowUp waitlist;
    private final Clock clock;

    public CancelBookingUseCase(
            UnitOfWork unitOfWork,
            BookingRepository bookings,
            InventoryEngines engines,
            CalendarReader calendars,
            BookingEventPublisher events,
            WaitlistFollowUp waitlist,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.bookings = bookings;
        this.engines = engines;
        this.calendars = calendars;
        this.events = events;
        this.waitlist = waitlist;
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
            String policyCode = policyCode(booking, now);
            engines.of(booking.offeringKind()).release(booking.id().value());
            booking.cancel(now, policyCode);
            bookings.update(booking);
            events.cancelled(booking, now);
            if (!waitlist.lapse(booking.id().value(), WaitlistFollowUp.BOOKING_CANCELLED)) {
                waitlist.wake(booking.offeringId(), booking.sessionId());
            }
            return booking;
        });
    }

    private String policyCode(Booking booking, Instant now) {
        if (booking.status() != BookingStatus.CONFIRMED || booking.startsAt() == null) {
            return BookingEvents.NO_POLICY;
        }
        if (!now.isBefore(booking.startsAt())) {
            return AFTER_START;
        }
        long minutes = Duration.between(now, booking.startsAt()).toMinutes();
        for (CancellationWindow window : calendars.cancellationWindows(booking.offeringId())) {
            if (window.minutesBeforeStart() <= minutes) {
                return window.code();
            }
        }
        return BookingEvents.NO_POLICY;
    }
}
