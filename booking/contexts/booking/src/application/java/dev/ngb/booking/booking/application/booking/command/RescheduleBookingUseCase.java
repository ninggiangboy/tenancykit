package dev.ngb.booking.booking.application.booking.command;

import dev.ngb.booking.booking.application.booking.exception.BookingProblem;
import dev.ngb.booking.booking.application.booking.exception.BookingStateException;
import dev.ngb.booking.booking.application.booking.exception.SlotTakenException;
import dev.ngb.booking.booking.application.booking.exception.SoldOutException;
import dev.ngb.booking.booking.application.booking.port.BookingRepository;
import dev.ngb.booking.booking.application.booking.service.BookingEventPublisher;
import dev.ngb.booking.booking.application.booking.service.BookingRules;
import dev.ngb.booking.booking.application.booking.service.InventoryEngines;
import dev.ngb.booking.booking.domain.booking.AssignedResource;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingEvents;
import dev.ngb.booking.booking.domain.booking.BookingStatus;
import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.catalog.api.calendar.OfferingCalendar;
import dev.ngb.booking.inventory.api.assignment.ResourceAssignmentPlanner;
import dev.ngb.booking.inventory.api.assignment.ResourceAssignmentPlanner.Assignment;
import dev.ngb.booking.inventory.api.assignment.ResourceAssignmentPlanner.Slot;
import dev.ngb.booking.inventory.api.engine.CapacityClaim;
import dev.ngb.booking.inventory.api.engine.ClaimOutcome;
import dev.ngb.booking.inventory.api.engine.Conflict;
import dev.ngb.booking.inventory.api.engine.IntervalClaim;
import dev.ngb.booking.inventory.api.engine.InventoryEngine;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Moves a booking to a new place in one transaction.
 *
 * <p>The old claims are released and the new ones taken in the same transaction, so either the booking is at the new
 * place or nothing changed: a refusal rolls the release back. Releasing first is also what lets an interval booking
 * shift by minutes inside its own range, because the exclusion constraint never sees both ranges at once.
 */
public class RescheduleBookingUseCase {

    private static final String CAPACITY = "CAPACITY";
    private static final long SECONDS_PER_MINUTE = 60L;

    public record Command(
            UUID sessionId, String poolCode, Instant start, Integer durationMinutes, UUID resourceId) {}

    private final UnitOfWork unitOfWork;
    private final BookingRepository bookings;
    private final BookingRules rules;
    private final InventoryEngines engines;
    private final CalendarReader calendars;
    private final ResourceAssignmentPlanner planner;
    private final BookingEventPublisher events;
    private final Clock clock;
    private final long graceSeconds;

    public RescheduleBookingUseCase(
            UnitOfWork unitOfWork,
            BookingRepository bookings,
            BookingRules rules,
            InventoryEngines engines,
            CalendarReader calendars,
            ResourceAssignmentPlanner planner,
            BookingEventPublisher events,
            Clock clock,
            long graceSeconds) {
        this.unitOfWork = unitOfWork;
        this.bookings = bookings;
        this.rules = rules;
        this.engines = engines;
        this.calendars = calendars;
        this.planner = planner;
        this.events = events;
        this.clock = clock;
        this.graceSeconds = graceSeconds;
    }

    public Booking execute(UUID bookingId, Command command) {
        return unitOfWork.transactional(() -> {
            Booking booking = bookings
                    .lockById(bookingId)
                    .orElseThrow(() -> new NotFoundException("No booking exists here with that id."));
            if (booking.status() != BookingStatus.HELD && booking.status() != BookingStatus.CONFIRMED) {
                throw new BookingStateException("Only a held or confirmed booking can move");
            }
            OfferingCalendar offering = calendars
                    .offering(booking.offeringId())
                    .orElseThrow(() -> new NotFoundException("No offering exists here with that id."));
            Instant now = clock.now();
            BookingEvents.Place previous = BookingEvents.placeOf(booking);
            InventoryEngine engine = engines.of(offering.kind());
            Instant expiresAt = booking.status() == BookingStatus.CONFIRMED ? null : booking.holdExpiresAt();

            if (CAPACITY.equals(offering.kind())) {
                moveCapacity(engine, booking, offering, command, expiresAt, now);
            } else {
                moveInterval(engine, booking, offering, command, expiresAt, now);
            }
            bookings.update(booking);
            events.rescheduled(booking, previous, now);
            return booking;
        });
    }

    private void moveCapacity(
            InventoryEngine engine,
            Booking booking,
            OfferingCalendar offering,
            Command command,
            Instant expiresAt,
            Instant now) {
        UUID sessionId = command.sessionId() != null ? command.sessionId() : booking.sessionId();
        String poolCode =
                command.poolCode() != null ? rules.poolCode(command.poolCode()) : booking.poolCode();
        rules.session(offering, sessionId, poolCode);
        engine.release(booking.id().value());
        ClaimOutcome outcome = engine.claim(
                new CapacityClaim(
                        booking.id().value(), sessionId, poolCode, booking.qty(), expiresAt, now, graceSeconds));
        if (outcome instanceof Conflict conflict) {
            throw conflict(conflict);
        }
        if (!booking.reschedule(sessionId, poolCode, null, null, List.of(), now)) {
            throw new BookingStateException("The booking is no longer held or confirmed");
        }
    }

    private void moveInterval(
            InventoryEngine engine,
            Booking booking,
            OfferingCalendar offering,
            Command command,
            Instant expiresAt,
            Instant now) {
        Instant start = command.start() != null ? command.start() : booking.startsAt();
        Integer duration = command.durationMinutes() != null
                ? command.durationMinutes()
                : durationOf(booking);
        BookingRules.Window window = rules.intervalWindow(offering, start, duration);
        engine.release(booking.id().value());
        Assignment assignment = assignment(offering, window, command.resourceId());
        List<AssignedResource> resources = resourcesOf(assignment);
        for (AssignedResource resource : resources) {
            ClaimOutcome outcome = engine.claim(new IntervalClaim(
                    booking.id().value(),
                    resource.resourceId(),
                    window.start(),
                    window.end(),
                    offering.bufferBefore() * SECONDS_PER_MINUTE,
                    offering.bufferAfter() * SECONDS_PER_MINUTE,
                    expiresAt,
                    now,
                    graceSeconds));
            if (outcome instanceof Conflict conflict) {
                throw conflict(conflict);
            }
        }
        if (!booking.reschedule(null, null, window.start(), window.end(), resources, now)) {
            throw new BookingStateException("The booking is no longer held or confirmed");
        }
        bookings.replaceResources(booking.id().value(), resources);
    }

    private Assignment assignment(OfferingCalendar offering, BookingRules.Window window, UUID pinned) {
        long bufferBefore = offering.bufferBefore() * SECONDS_PER_MINUTE;
        long bufferAfter = offering.bufferAfter() * SECONDS_PER_MINUTE;
        Instant bufferedStart = window.start().minusSeconds(bufferBefore);
        Instant bufferedEnd = window.end().plusSeconds(bufferAfter);
        List<Assignment> assignments = planner.assignments(new ResourceAssignmentPlanner.Request(
                offering,
                window.start(),
                window.end(),
                bufferBefore,
                bufferAfter,
                pinned,
                null,
                calendars::resource,
                calendars::group,
                resourceId -> calendars.blocks(resourceId, bufferedStart, bufferedEnd)));
        if (assignments.isEmpty()) {
            throw new SlotTakenException("No resource can fill every requirement of this offering.");
        }
        return assignments.get(0);
    }

    /** The resources of the new place in ascending id, the order the claims are inserted in. */
    private static List<AssignedResource> resourcesOf(Assignment assignment) {
        List<AssignedResource> resources = new ArrayList<>();
        for (Slot slot : assignment.slots()) {
            for (UUID resourceId : slot.resourceIds()) {
                resources.add(new AssignedResource(slot.slotCode(), resourceId));
            }
        }
        resources.sort(Comparator.comparing(AssignedResource::resourceId));
        return List.copyOf(resources);
    }

    private static int durationOf(Booking booking) {
        if (booking.startsAt() == null || booking.endsAt() == null) {
            return 0;
        }
        return (int) Duration.between(booking.startsAt(), booking.endsAt()).toMinutes();
    }

    private static BookingProblem conflict(Conflict conflict) {
        if (Conflict.SOLD_OUT.equals(conflict.code())) {
            return new SoldOutException(conflict.detail());
        }
        return new SlotTakenException(conflict.detail());
    }
}
