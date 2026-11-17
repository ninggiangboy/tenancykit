package dev.ngb.booking.booking.application.booking.service;

import dev.ngb.booking.booking.application.booking.exception.InvalidBookingException;
import dev.ngb.booking.booking.application.booking.exception.OutsideOpenHoursException;
import dev.ngb.booking.booking.domain.booking.AssignedResource;
import dev.ngb.booking.booking.domain.booking.BookingSource;
import dev.ngb.booking.catalog.api.calendar.CalendarExceptionRange;
import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.catalog.api.calendar.GroupCalendar;
import dev.ngb.booking.catalog.api.calendar.GroupMemberRef;
import dev.ngb.booking.catalog.api.calendar.OfferingCalendar;
import dev.ngb.booking.catalog.api.calendar.OfferingRequirement;
import dev.ngb.booking.catalog.api.calendar.ResourceCalendar;
import dev.ngb.booking.catalog.api.calendar.SessionCalendar;
import dev.ngb.booking.catalog.api.calendar.WorkingHourWindow;
import dev.ngb.core.error.NotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a booking body has to satisfy before anything is claimed: the customer reference the Tenant sent, the places of
 * a capacity session, or the range and the resources of an interval offering.
 *
 * <p>The rules answer with the values a use case then decides with, so a use case never re-reads a raw request field,
 * and every refusal carries its own stable code. The open-hours rule resolves the resource's own zone rather than
 * adding offsets, so a day with 23 or 25 local hours is judged correctly.
 */
public class BookingRules {

    private static final String CAPACITY = "CAPACITY";

    private static final int MAX_CUSTOMER_REF = 200;

    private static final int MIN_POOL_CODE = 1;

    private static final int MAX_POOL_CODE = 60;

    private static final int SECONDS_PER_MINUTE = 60;

    private static final int MINUTES_PER_DAY = 1440;

    private static final String CLOSED = "CLOSED";

    private static final String SCHEDULED = "SCHEDULED";

    private final CalendarReader calendars;

    public BookingRules(CalendarReader calendars) {
        this.calendars = calendars;
    }

    /** @return the Tenant's reference for its customer, trimmed, which is between 1 and 200 characters */
    public String customerRef(String raw) {
        String reference = raw == null ? null : raw.strip();
        if (reference == null || reference.isEmpty() || reference.length() > MAX_CUSTOMER_REF) {
            throw new InvalidBookingException(
                    "customerRef must be between 1 and " + MAX_CUSTOMER_REF + " characters");
        }
        return reference;
    }

    /** @return the source the hold came from, defaulting to the Tenant System that called */
    public BookingSource source(BookingSource source) {
        return source == null ? BookingSource.TENANT : source;
    }

    /** @return how many places the booking takes, which the offering caps */
    public int qty(OfferingCalendar offering, Integer raw) {
        int qty = raw == null ? 1 : raw;
        if (qty < 1) {
            throw new InvalidBookingException("qty must be at least 1");
        }
        if (qty > offering.maxQty()) {
            throw new InvalidBookingException(
                    "qty must be at most " + offering.maxQty() + " for this offering");
        }
        return qty;
    }

    /** @return the session the places come from, which belongs to this offering, is still selling and holds the pool */
    public SessionCalendar session(OfferingCalendar offering, UUID sessionId, String pool) {
        if (!CAPACITY.equals(offering.kind())) {
            throw new InvalidBookingException("An offering that sells a range takes no sessionId");
        }
        if (sessionId == null) {
            throw new InvalidBookingException("sessionId is required for an offering that sells places");
        }
        SessionCalendar session = calendars
                .session(sessionId)
                .orElseThrow(() -> new InvalidBookingException("No session exists here with that id."));
        if (!session.offeringId().equals(offering.offeringId())) {
            throw new InvalidBookingException("The session belongs to another offering");
        }
        if (!SCHEDULED.equals(session.status())) {
            throw new InvalidBookingException("The session is " + session.status() + " and sells no place");
        }
        boolean known = session.pools().stream().anyMatch(candidate -> candidate.code().equals(pool));
        if (!known) {
            throw new InvalidBookingException("The session has no pool " + pool);
        }
        return session;
    }

    /**
     * The range and the resource each of the offering's requirements takes.
     *
     * <p>A requirement that names its own resource takes it; one that names a group takes the group's highest-priority
     * member. A caller that pinned a resource keeps only the requirements that resource fills, which is what makes a
     * pinned create a request for that resource rather than a hint.
     *
     * @throws NotFoundException when the caller pinned a resource no requirement of this offering can fill
     */
    public IntervalPlan interval(OfferingCalendar offering, Instant start, Integer durationMinutes, UUID resourceId) {
        if (start == null) {
            throw new InvalidBookingException("start is required for an offering that sells a range");
        }
        int duration = duration(offering, durationMinutes);
        Instant end = start.plusSeconds((long) duration * SECONDS_PER_MINUTE);
        List<AssignedResource> resources = assign(offering, resourceId);
        for (AssignedResource resource : resources) {
            requireOpen(resource.resourceId(), start, end);
        }
        return new IntervalPlan(start, end, resources);
    }

    /** A range to claim and the resource of every requirement it fills, in requirement order. */
    public record IntervalPlan(Instant start, Instant end, List<AssignedResource> resources) {}

    /** @return the pool code the Tenant sent, trimmed, which is between 1 and 60 characters */
    public String poolCode(String raw) {
        String code = raw == null ? null : raw.strip();
        if (code == null || code.length() < MIN_POOL_CODE || code.length() > MAX_POOL_CODE) {
            throw new InvalidBookingException(
                    "poolCode must be between " + MIN_POOL_CODE + " and " + MAX_POOL_CODE + " characters");
        }
        return code;
    }

    private static int duration(OfferingCalendar offering, Integer requested) {
        int min = offering.durationMin();
        int max = offering.durationMax() == null ? min : offering.durationMax();
        int duration = requested == null ? min : requested;
        if (duration < min || duration > max) {
            throw new InvalidBookingException("The duration must be between " + min + " and " + max + " minutes");
        }
        if (offering.durationStep() < 1 || Math.floorMod(duration - min, offering.durationStep()) != 0) {
            throw new InvalidBookingException("The duration is not on the offering's duration step");
        }
        return duration;
    }

    private List<AssignedResource> assign(OfferingCalendar offering, UUID pinned) {
        if (offering.requirements().isEmpty()) {
            throw new InvalidBookingException("The offering has no requirement a booking can fill");
        }
        List<AssignedResource> assigned = new ArrayList<>(offering.requirements().size());
        boolean pinnedResolved = false;
        for (OfferingRequirement requirement : offering.requirements()) {
            UUID candidate = candidate(requirement);
            if (pinned != null && !pinned.equals(candidate)) {
                continue;
            }
            if (candidate == null) {
                throw new InvalidBookingException(
                        "The requirement " + requirement.slotCode() + " names no resource");
            }
            if (pinned != null) {
                pinnedResolved = true;
            }
            assigned.add(new AssignedResource(requirement.slotCode(), candidate));
        }
        if (pinned != null && !pinnedResolved) {
            throw new NotFoundException("No resource exists here with that id.");
        }
        if (assigned.isEmpty()) {
            throw new InvalidBookingException("The offering has no requirement a booking can fill");
        }
        return List.copyOf(assigned);
    }

    /** The requirement's own resource, or the member of its group that sits first for the priority strategy. */
    private UUID candidate(OfferingRequirement requirement) {
        if (requirement.resourceId() != null) {
            return requirement.resourceId();
        }
        if (requirement.groupId() == null) {
            return null;
        }
        GroupCalendar group = calendars.group(requirement.groupId()).orElse(null);
        if (group == null) {
            return null;
        }
        UUID best = null;
        int bestPriority = Integer.MAX_VALUE;
        for (GroupMemberRef member : group.members()) {
            if (member.priority() < bestPriority) {
                bestPriority = member.priority();
                best = member.resourceId();
            }
        }
        return best;
    }

    private void requireOpen(UUID resourceId, Instant start, Instant end) {
        ResourceCalendar calendar = calendars
                .resource(resourceId)
                .orElseThrow(() -> new NotFoundException("No resource exists here with that id."));
        if (!open(calendar, start, end)) {
            throw new OutsideOpenHoursException("The range is outside the resource's open hours");
        }
    }

    /** Whether a service range sits inside one of the resource's open windows, resolved in the resource's own zone. */
    private static boolean open(ResourceCalendar calendar, Instant start, Instant end) {
        ZoneId zone = ZoneId.of(calendar.zone());
        LocalDate first = start.atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = end.atZone(zone).toLocalDate().plusDays(1);
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            for (WorkingHourWindow window : windows(calendar, date)) {
                Instant opens = instantAt(date, window.opensMinute(), zone);
                Instant closes = instantAt(date, window.closesMinute(), zone);
                if (!start.isBefore(opens) && !end.isAfter(closes)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<WorkingHourWindow> windows(ResourceCalendar calendar, LocalDate date) {
        for (CalendarExceptionRange exception : calendar.exceptions()) {
            if (date.isBefore(exception.fromDate()) || date.isAfter(exception.toDate())) {
                continue;
            }
            return CLOSED.equals(exception.kind()) ? List.of() : exception.hours();
        }
        int weekday = date.getDayOfWeek().getValue();
        List<WorkingHourWindow> weekly = new ArrayList<>();
        for (WorkingHourWindow window : calendar.hours()) {
            if (window.weekday() == weekday) {
                weekly.add(window);
            }
        }
        return weekly;
    }

    private static Instant instantAt(LocalDate date, int minute, ZoneId zone) {
        LocalDate day = minute >= MINUTES_PER_DAY ? date.plusDays(1) : date;
        int fromMidnight = minute % MINUTES_PER_DAY;
        return ZonedDateTime.of(day, LocalTime.of(fromMidnight / 60, fromMidnight % 60), zone).toInstant();
    }
}
