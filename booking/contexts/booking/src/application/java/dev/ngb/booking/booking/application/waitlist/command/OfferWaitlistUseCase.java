package dev.ngb.booking.booking.application.waitlist.command;

import dev.ngb.booking.booking.application.booking.command.CreateBookingUseCase;
import dev.ngb.booking.booking.application.booking.exception.BookingProblem;
import dev.ngb.booking.booking.application.waitlist.port.WaitlistRepository;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingSource;
import dev.ngb.booking.booking.domain.waitlist.WaitlistEntry;
import dev.ngb.booking.booking.domain.waitlist.WaitlistEvents;
import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.catalog.api.calendar.OfferingCalendar;
import dev.ngb.booking.inventory.api.assignment.ResourceAssignmentPlanner;
import dev.ngb.booking.inventory.api.capacity.PoolAvailability;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * The matcher: for a target whose place freed, tries the waiting entries oldest first and offers the first one that
 * fits a hold.
 *
 * <p>The offer is an ordinary hold created through the normal use case with source {@code WAITLIST} and the offer
 * window as its TTL, so it takes the same claim and conflict path a direct hold does. An entry a free place does not
 * fit stays waiting and keeps its position; the first success ends the pass, because one wake-up is one freed place.
 */
public class OfferWaitlistUseCase {

    private static final int BATCH = 100;
    private static final String CAPACITY = "CAPACITY";
    private static final long SECONDS_PER_MINUTE = 60L;

    private final UnitOfWork unitOfWork;
    private final WaitlistRepository entries;
    private final CreateBookingUseCase creates;
    private final CalendarReader calendars;
    private final ResourceAssignmentPlanner planner;
    private final PoolAvailability pools;
    private final EventPublisher events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;
    private final long offerWindowSeconds;

    public OfferWaitlistUseCase(
            UnitOfWork unitOfWork,
            WaitlistRepository entries,
            CreateBookingUseCase creates,
            CalendarReader calendars,
            ResourceAssignmentPlanner planner,
            PoolAvailability pools,
            EventPublisher events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock,
            long offerWindowSeconds) {
        this.unitOfWork = unitOfWork;
        this.entries = entries;
        this.creates = creates;
        this.calendars = calendars;
        this.planner = planner;
        this.pools = pools;
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
        this.offerWindowSeconds = offerWindowSeconds;
    }

    /** @return how many entries this pass offered a hold */
    public int execute() {
        List<String> scopes = unitOfWork.transactional(() -> entries.claimWakeups(BATCH));
        if (scopes.isEmpty()) {
            scopes = unitOfWork.transactional(() -> entries.waitingScopes(BATCH));
        }
        int offered = 0;
        for (String scope : new LinkedHashSet<>(scopes)) {
            try {
                if (offerScope(scope)) {
                    offered++;
                }
            } catch (BookingProblem | NotFoundException refused) {
                // The place was taken by a direct hold between the read and the claim; the next pass retries the line.
            }
        }
        return offered;
    }

    private boolean offerScope(String scope) {
        return unitOfWork.transactional(() -> {
            Instant now = clock.now();
            for (WaitlistEntry entry : entries.lockWaiting(scope, BATCH)) {
                Optional<Booking> booking = tryHold(entry, now);
                if (booking.isEmpty()) {
                    continue;
                }
                int position = entries.waitingAhead(entry);
                if (!entry.offer(booking.get().id().value(), booking.get().holdExpiresAt(), now)) {
                    continue;
                }
                entries.update(entry);
                OfferingCalendar offering = calendars.offering(entry.offeringId()).orElse(null);
                events.publish(WaitlistEvents.offered(
                        ids.newId(),
                        tenantContext.requireTenant(),
                        entry,
                        offering == null ? CAPACITY : offering.kind(),
                        position,
                        booking.get().id().value(),
                        booking.get().holdExpiresAt(),
                        now));
                return true;
            }
            return false;
        });
    }

    /**
     * Tries the entry's own claim path, after a read that says the target can take it.
     *
     * <p>The read keeps a mismatch out of the transaction: a claim that fails inside it would mark the transaction
     * rollback-only and take the whole pass with it. A race the read cannot see is caught outside the transaction, and
     * the entry stays waiting for the next pass.
     */
    private Optional<Booking> tryHold(WaitlistEntry entry, Instant now) {
        OfferingCalendar offering = calendars.offering(entry.offeringId()).orElse(null);
        if (offering == null) {
            return Optional.empty();
        }
        Integer duration = null;
        if (CAPACITY.equals(offering.kind())) {
            if (entry.sessionId() == null
                    || pools.remaining(entry.sessionId(), entry.poolCode()) < entry.qty()) {
                return Optional.empty();
            }
        } else {
            if (entry.desiredStartsAt() == null) {
                return Optional.empty();
            }
            duration = entry.desiredEndsAt() == null
                    ? offering.durationMin()
                    : (int) Duration.between(entry.desiredStartsAt(), entry.desiredEndsAt())
                            .toMinutes();
            if (duration < 1 || !rangeFree(offering, entry, duration)) {
                return Optional.empty();
            }
        }
        return Optional.of(creates
                .execute(new CreateBookingUseCase.Command(
                        entry.offeringId(),
                        entry.customerRef(),
                        entry.sessionId(),
                        entry.poolCode(),
                        entry.qty(),
                        entry.desiredStartsAt(),
                        duration,
                        entry.resourceId(),
                        null,
                        null,
                        BookingSource.WAITLIST,
                        null,
                        offerWindowSeconds))
                .booking());
    }

    private boolean rangeFree(OfferingCalendar offering, WaitlistEntry entry, int duration) {
        long bufferBefore = offering.bufferBefore() * SECONDS_PER_MINUTE;
        long bufferAfter = offering.bufferAfter() * SECONDS_PER_MINUTE;
        Instant end = entry.desiredStartsAt().plusSeconds(duration * SECONDS_PER_MINUTE);
        Instant from = entry.desiredStartsAt().minusSeconds(bufferBefore);
        Instant to = end.plusSeconds(bufferAfter);
        return !planner
                .assignments(new ResourceAssignmentPlanner.Request(
                        offering,
                        entry.desiredStartsAt(),
                        end,
                        bufferBefore,
                        bufferAfter,
                        entry.resourceId(),
                        null,
                        calendars::resource,
                        calendars::group,
                        resourceId -> calendars.blocks(resourceId, from, to)))
                .isEmpty();
    }
}
