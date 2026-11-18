package dev.ngb.booking.booking.application.waitlist.service;

import dev.ngb.booking.booking.application.waitlist.port.WaitlistRepository;
import dev.ngb.booking.booking.domain.waitlist.WaitlistEntry;
import dev.ngb.booking.booking.domain.waitlist.WaitlistEvents;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Follows an offered hold to its outcome and wakes the next entry.
 *
 * <p>Confirming the hold accepts the entry. A cancellation or expiry lapses it, announces the reason and writes a
 * wake-up so the worker tries the target again. A plain release wakes the target without touching any entry: a freed
 * place is worth a pass even when no offer was outstanding.
 */
public class WaitlistFollowUp {

    public static final String BOOKING_CANCELLED = "BOOKING_CANCELLED";
    public static final String BOOKING_EXPIRED = "BOOKING_EXPIRED";

    private final UnitOfWork unitOfWork;
    private final WaitlistRepository entries;
    private final EventPublisher events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public WaitlistFollowUp(
            UnitOfWork unitOfWork,
            WaitlistRepository entries,
            EventPublisher events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.entries = entries;
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    /** The offered hold was confirmed: the entry stops waiting. */
    public void accept(UUID bookingId) {
        unitOfWork.transactional(() -> {
            entries.lockByOfferedBooking(bookingId).ifPresent(entry -> {
                if (entry.accept(clock.now())) {
                    entries.update(entry);
                }
            });
            return null;
        });
    }

    /**
     * The offered hold ended without an acceptance.
     *
     * @return true when an entry was offered this booking and is now lapsed
     */
    public boolean lapse(UUID bookingId, String reason) {
        return unitOfWork.transactional(() -> {
            Optional<WaitlistEntry> found = entries.lockByOfferedBooking(bookingId);
            if (found.isEmpty()) {
                return false;
            }
            WaitlistEntry entry = found.get();
            Instant now = clock.now();
            if (!entry.lapse(now)) {
                return false;
            }
            entries.update(entry);
            events.publish(WaitlistEvents.lapsed(
                    ids.newId(), tenantContext.requireTenant(), entry, entry.offeredBookingId(), reason, now));
            entries.wakeup(entry.scope());
            return true;
        });
    }

    /** A place or range was released: the worker gets one pass for that target. */
    public void wake(UUID offeringId, UUID sessionId) {
        entries.wakeup(WaitlistRepository.scopeOf(offeringId, sessionId));
    }
}
