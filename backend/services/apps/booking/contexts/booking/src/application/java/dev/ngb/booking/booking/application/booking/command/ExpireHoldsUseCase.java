package dev.ngb.booking.booking.application.booking.command;

import dev.ngb.booking.booking.application.booking.port.BookingRepository;
import dev.ngb.booking.booking.application.booking.service.BookingEventPublisher;
import dev.ngb.booking.booking.application.booking.service.InventoryEngines;
import dev.ngb.booking.booking.application.waitlist.service.WaitlistFollowUp;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.List;

/**
 * Reclaims the holds nobody confirmed before their grace ran out.
 *
 * <p>A hold is reclaimed once its expiry plus the grace window has passed, not the instant it expires, so a
 * confirmation that arrives just late still finds its place. Each lapsed hold is locked with {@code skip locked}, so a
 * second worker skips it rather than waiting, and one pass handles at most a batch because the work belongs to a
 * schedule rather than to one request.
 */
public class ExpireHoldsUseCase {

    private final UnitOfWork unitOfWork;
    private final BookingRepository bookings;
    private final InventoryEngines engines;
    private final BookingEventPublisher events;
    private final WaitlistFollowUp waitlist;
    private final Clock clock;
    private final int batchSize;
    private final long graceSeconds;

    public ExpireHoldsUseCase(
            UnitOfWork unitOfWork,
            BookingRepository bookings,
            InventoryEngines engines,
            BookingEventPublisher events,
            WaitlistFollowUp waitlist,
            Clock clock,
            int batchSize,
            long graceSeconds) {
        this.unitOfWork = unitOfWork;
        this.bookings = bookings;
        this.engines = engines;
        this.events = events;
        this.waitlist = waitlist;
        this.clock = clock;
        this.batchSize = batchSize;
        this.graceSeconds = graceSeconds;
    }

    /** @return how many holds this pass expired */
    public int execute() {
        return unitOfWork.transactional(() -> {
            Instant now = clock.now();
            Instant cutoff = now.minusSeconds(graceSeconds);
            List<Booking> lapsed = bookings.lockExpired(cutoff, batchSize);
            int expired = 0;
            for (Booking booking : lapsed) {
                engines.of(booking.offeringKind()).release(booking.id().value());
                booking.expire(now);
                bookings.update(booking);
                events.expired(booking, now);
                if (!waitlist.lapse(booking.id().value(), WaitlistFollowUp.BOOKING_EXPIRED)) {
                    waitlist.wake(booking.offeringId(), booking.sessionId());
                }
                expired++;
            }
            return expired;
        });
    }
}
