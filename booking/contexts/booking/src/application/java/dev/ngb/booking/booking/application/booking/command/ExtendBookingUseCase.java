package dev.ngb.booking.booking.application.booking.command;

import dev.ngb.booking.booking.application.booking.exception.BookingStateException;
import dev.ngb.booking.booking.application.booking.exception.ExtensionCapException;
import dev.ngb.booking.booking.application.booking.exception.InvalidBookingException;
import dev.ngb.booking.booking.application.booking.port.BookingRepository;
import dev.ngb.booking.booking.application.booking.service.InventoryEngines;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingStatus;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.UUID;

/**
 * Lengthens a hold once, by a number of seconds inside the tenant's cap.
 *
 * <p>The booking is read under its row lock, so an extension and a confirmation that arrive together decide in order.
 * A hold that is no longer held, or one that was already extended, is refused: the extension is a one-time grace the
 * Tenant grants a customer, not a setting anybody may raise in a loop.
 */
public class ExtendBookingUseCase {

    private final UnitOfWork unitOfWork;
    private final BookingRepository bookings;
    private final InventoryEngines engines;
    private final Clock clock;
    private final int maxExtensionSeconds;

    public ExtendBookingUseCase(
            UnitOfWork unitOfWork,
            BookingRepository bookings,
            InventoryEngines engines,
            Clock clock,
            int maxExtensionSeconds) {
        this.unitOfWork = unitOfWork;
        this.bookings = bookings;
        this.engines = engines;
        this.clock = clock;
        this.maxExtensionSeconds = maxExtensionSeconds;
    }

    public Booking execute(UUID bookingId, Integer seconds) {
        return unitOfWork.transactional(() -> {
            Booking booking = bookings
                    .lockById(bookingId)
                    .orElseThrow(() -> new NotFoundException("No booking exists here with that id."));
            if (booking.status() != BookingStatus.HELD) {
                throw new BookingStateException(
                        "The booking is " + booking.status() + " and its hold cannot be extended.");
            }
            if (booking.extendedSeconds() > 0) {
                throw new BookingStateException("The hold has already been extended once.");
            }
            int extension = seconds == null ? 0 : seconds;
            if (extension < 1) {
                throw new InvalidBookingException("seconds must be at least 1");
            }
            if ((long) booking.extendedSeconds() + extension > maxExtensionSeconds) {
                throw new ExtensionCapException("The extension would pass this tenant's cap of "
                        + maxExtensionSeconds + " seconds.");
            }
            Instant now = clock.now();
            Instant newExpiry = booking.holdExpiresAt().plusSeconds(extension);
            engines.of(booking.offeringKind()).extend(booking.id().value(), newExpiry);
            booking.extend(newExpiry, extension, now);
            bookings.update(booking);
            return booking;
        });
    }
}
