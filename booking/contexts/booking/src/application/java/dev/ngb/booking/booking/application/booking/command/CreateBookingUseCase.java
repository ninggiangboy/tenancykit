package dev.ngb.booking.booking.application.booking.command;

import dev.ngb.booking.booking.application.booking.exception.BookingProblem;
import dev.ngb.booking.booking.application.booking.exception.SlotTakenException;
import dev.ngb.booking.booking.application.booking.exception.SoldOutException;
import dev.ngb.booking.booking.application.booking.port.BookingRepository;
import dev.ngb.booking.booking.application.booking.service.BookingEventPublisher;
import dev.ngb.booking.booking.application.booking.service.BookingRules;
import dev.ngb.booking.booking.application.booking.service.InventoryEngines;
import dev.ngb.booking.booking.domain.booking.AssignedResource;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingId;
import dev.ngb.booking.booking.domain.booking.BookingSource;
import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.catalog.api.calendar.OfferingCalendar;
import dev.ngb.booking.inventory.api.engine.CapacityClaim;
import dev.ngb.booking.inventory.api.engine.ClaimOutcome;
import dev.ngb.booking.inventory.api.engine.Conflict;
import dev.ngb.booking.inventory.api.engine.IntervalClaim;
import dev.ngb.booking.inventory.api.engine.InventoryEngine;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Holds a place or a range for a customer, in one transaction with the claim behind it.
 *
 * <p>Which engine claims it is the offering's kind: a capacity offering claims places in one of a session's pools, an
 * interval offering claims a buffered range on every resource its requirements name. The hold is created through the
 * database's unique idempotency key rather than a read that could race, so a repeated request returns the booking the
 * first one wrote, and a reuse of the key with another body is refused.
 */
public class CreateBookingUseCase {

    private static final String CAPACITY = "CAPACITY";

    private static final long SECONDS_PER_MINUTE = 60L;

    /** A hold as the caller sent it; the kind of the offering decides which of the optional fields are read. */
    public record Command(
            UUID offeringId,
            String customerRef,
            UUID sessionId,
            String poolCode,
            Integer qty,
            Instant start,
            Integer durationMinutes,
            UUID resourceId,
            Integer partySize,
            String idempotencyKey,
            BookingSource source) {}

    /** The booking that now exists, and whether this call made it or replayed an earlier one. */
    public record Result(Booking booking, boolean replayed) {}

    private final UnitOfWork unitOfWork;
    private final BookingRepository bookings;
    private final BookingRules rules;
    private final InventoryEngines engines;
    private final CalendarReader calendars;
    private final BookingEventPublisher events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;
    private final long holdTtlSeconds;
    private final long graceSeconds;

    public CreateBookingUseCase(
            UnitOfWork unitOfWork,
            BookingRepository bookings,
            BookingRules rules,
            InventoryEngines engines,
            CalendarReader calendars,
            BookingEventPublisher events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock,
            long holdTtlSeconds,
            long graceSeconds) {
        this.unitOfWork = unitOfWork;
        this.bookings = bookings;
        this.rules = rules;
        this.engines = engines;
        this.calendars = calendars;
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
        this.holdTtlSeconds = holdTtlSeconds;
        this.graceSeconds = graceSeconds;
    }

    public Result execute(Command command) {
        return unitOfWork.transactional(() -> {
            return create(command);
        });
    }

    private Result create(Command command) {
        OfferingCalendar offering = calendars
                .offering(command.offeringId())
                .orElseThrow(() -> new NotFoundException("No offering exists here with that id."));
        if (offering.kind() == null) {
            throw new NotFoundException("No offering exists here with that id.");
        }
        String key = command.idempotencyKey();
        String fingerprint = key == null ? null : Fingerprint.of(
                command.offeringId(),
                command.customerRef(),
                command.sessionId(),
                command.poolCode(),
                command.qty(),
                command.start(),
                command.durationMinutes(),
                command.resourceId(),
                command.partySize(),
                command.source());
        if (key != null) {
            Optional<Booking> stored = bookings.findByIdempotencyKey(key);
            if (stored.isPresent()) {
                Booking booking = stored.get();
                Fingerprint.requireSameRequest("booking", key, booking.requestFingerprint(), fingerprint);
                return new Result(booking, true);
            }
        }

        Instant now = clock.now();
        long ttl = offering.holdTtlSeconds() != null ? offering.holdTtlSeconds() : holdTtlSeconds;
        Instant expiresAt = now.plusSeconds(ttl);
        String kind = offering.kind();
        BookingId bookingId = new BookingId(ids.newId());
        InventoryEngine engine = engines.of(kind);
        int qty = rules.qty(offering, command.qty());
        String customerRef = rules.customerRef(command.customerRef());
        BookingSource source = rules.source(command.source());

        UUID sessionId = null;
        String poolCode = null;
        Instant startsAt = null;
        Instant endsAt = null;
        List<AssignedResource> resources = List.of();

        if (CAPACITY.equals(kind)) {
            String pool = rules.poolCode(command.poolCode());
            rules.session(offering, command.sessionId(), pool);
            sessionId = command.sessionId();
            poolCode = pool;
            ClaimOutcome outcome =
                    engine.claim(new CapacityClaim(bookingId.value(), sessionId, pool, qty, expiresAt, now, graceSeconds));
            if (outcome instanceof Conflict conflict) {
                throw conflict(conflict);
            }
        } else {
            BookingRules.IntervalPlan plan =
                    rules.interval(offering, command.start(), command.durationMinutes(), command.resourceId());
            startsAt = plan.start();
            endsAt = plan.end();
            resources = plan.resources();
            for (AssignedResource resource : resources) {
                ClaimOutcome outcome = engine.claim(new IntervalClaim(
                        bookingId.value(),
                        resource.resourceId(),
                        plan.start(),
                        plan.end(),
                        offering.bufferBefore() * SECONDS_PER_MINUTE,
                        offering.bufferAfter() * SECONDS_PER_MINUTE,
                        expiresAt,
                        now,
                        graceSeconds));
                if (outcome instanceof Conflict conflict) {
                    throw conflict(conflict);
                }
            }
        }

        Booking booking = Booking.create(
                bookingId,
                tenantContext.requireTenant(),
                offering.offeringId(),
                kind,
                customerRef,
                qty,
                sessionId,
                poolCode,
                startsAt,
                endsAt,
                source,
                expiresAt,
                resources,
                key,
                fingerprint,
                now);

        if (!bookings.insertIfAbsent(booking)) {
            Booking stored = bookings
                    .findByIdempotencyKey(key)
                    .orElseThrow(() -> new IllegalStateException(
                            "the idempotency key is present but its booking is missing"));
            Fingerprint.requireSameRequest("booking", key, stored.requestFingerprint(), fingerprint);
            return new Result(stored, true);
        }
        bookings.insertResources(booking.id().value(), resources);
        events.held(booking, now);
        return new Result(booking, false);
    }

    private static BookingProblem conflict(Conflict conflict) {
        if (Conflict.SOLD_OUT.equals(conflict.code())) {
            return new SoldOutException(conflict.detail());
        }
        return new SlotTakenException(conflict.detail());
    }
}
