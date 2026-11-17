package dev.ngb.booking.booking.application.waitlist.command;

import dev.ngb.booking.booking.application.booking.exception.InvalidBookingException;
import dev.ngb.booking.booking.application.booking.service.BookingRules;
import dev.ngb.booking.booking.application.waitlist.exception.AlreadyWaitingException;
import dev.ngb.booking.booking.application.waitlist.exception.TargetAvailableException;
import dev.ngb.booking.booking.application.waitlist.exception.WaitlistFullException;
import dev.ngb.booking.booking.application.waitlist.port.WaitlistRepository;
import dev.ngb.booking.booking.domain.waitlist.WaitlistEntry;
import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.catalog.api.calendar.OfferingCalendar;
import dev.ngb.booking.inventory.api.assignment.ResourceAssignmentPlanner;
import dev.ngb.booking.inventory.api.capacity.PoolAvailability;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Puts a customer in line for a target that is currently full.
 *
 * <p>Joining a target with a free place is refused, not silently accepted, because nothing would ever offer the entry;
 * so the check is the same availability the hold path uses. One waiting entry per customer and target, and the target
 * has a length cap from the tenant's settings.
 */
public class JoinWaitlistUseCase {

    private static final String CAPACITY = "CAPACITY";
    private static final long SECONDS_PER_MINUTE = 60L;

    public record Command(
            UUID offeringId,
            String customerRef,
            Integer qty,
            UUID sessionId,
            String poolCode,
            Instant startsAt,
            Instant endsAt,
            UUID resourceId) {}

    public record Result(WaitlistEntry entry, int position) {}

    private final UnitOfWork unitOfWork;
    private final WaitlistRepository entries;
    private final BookingRules rules;
    private final CalendarReader calendars;
    private final ResourceAssignmentPlanner planner;
    private final PoolAvailability pools;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;
    private final int capPerTarget;

    public JoinWaitlistUseCase(
            UnitOfWork unitOfWork,
            WaitlistRepository entries,
            BookingRules rules,
            CalendarReader calendars,
            ResourceAssignmentPlanner planner,
            PoolAvailability pools,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock,
            int capPerTarget) {
        this.unitOfWork = unitOfWork;
        this.entries = entries;
        this.rules = rules;
        this.calendars = calendars;
        this.planner = planner;
        this.pools = pools;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
        this.capPerTarget = capPerTarget;
    }

    public Result execute(Command command) {
        return unitOfWork.transactional(() -> {
            OfferingCalendar offering = calendars
                    .offering(command.offeringId())
                    .orElseThrow(() -> new NotFoundException("No offering exists here with that id."));
            String customerRef = rules.customerRef(command.customerRef());
            int qty = command.qty() == null ? 1 : command.qty();
            if (qty < 1) {
                throw new InvalidBookingException("qty must be at least 1");
            }
            Instant now = clock.now();
            if (CAPACITY.equals(offering.kind())) {
                requireCapacityFull(offering, command, qty);
            } else {
                requireIntervalFull(offering, command);
            }
            String scope = WaitlistRepository.scopeOf(command.offeringId(), command.sessionId());
            if (entries.waitingCount(scope) >= capPerTarget) {
                throw new WaitlistFullException("This target's waitlist has reached the tenant's cap.");
            }
            WaitlistEntry entry = WaitlistEntry.join(
                    ids.newId(),
                    tenantContext.requireTenant(),
                    command.offeringId(),
                    customerRef,
                    command.sessionId(),
                    command.poolCode(),
                    command.startsAt(),
                    command.endsAt(),
                    qty,
                    command.resourceId(),
                    now);
            if (!entries.insertIfAbsent(entry)) {
                throw new AlreadyWaitingException();
            }
            return new Result(entry, entries.waitingAhead(entry));
        });
    }

    private void requireCapacityFull(OfferingCalendar offering, Command command, int qty) {
        if (command.sessionId() == null || command.poolCode() == null) {
            throw new InvalidBookingException("a capacity waitlist entry names a session and a pool");
        }
        String poolCode = rules.poolCode(command.poolCode());
        rules.session(offering, command.sessionId(), poolCode);
        if (pools.remaining(command.sessionId(), poolCode) >= qty) {
            throw new TargetAvailableException("The pool has enough free places to book now.");
        }
    }

    private void requireIntervalFull(OfferingCalendar offering, Command command) {
        if (command.startsAt() == null) {
            throw new InvalidBookingException("an interval waitlist entry names a desired start");
        }
        int duration = command.endsAt() == null
                ? offering.durationMin()
                : (int) Duration.between(command.startsAt(), command.endsAt()).toMinutes();
        if (duration < 1) {
            throw new InvalidBookingException("the desired range must end after it starts");
        }
        Instant end = command.startsAt().plusSeconds(duration * SECONDS_PER_MINUTE);
        long bufferBefore = offering.bufferBefore() * SECONDS_PER_MINUTE;
        long bufferAfter = offering.bufferAfter() * SECONDS_PER_MINUTE;
        Instant from = command.startsAt().minusSeconds(bufferBefore);
        Instant to = end.plusSeconds(bufferAfter);
        List<ResourceAssignmentPlanner.Assignment> assignments = planner.assignments(
                new ResourceAssignmentPlanner.Request(
                        offering,
                        command.startsAt(),
                        end,
                        bufferBefore,
                        bufferAfter,
                        command.resourceId(),
                        null,
                        calendars::resource,
                        calendars::group,
                        resourceId -> calendars.blocks(resourceId, from, to)));
        if (!assignments.isEmpty()) {
            throw new TargetAvailableException("The range is free to book now.");
        }
    }
}
