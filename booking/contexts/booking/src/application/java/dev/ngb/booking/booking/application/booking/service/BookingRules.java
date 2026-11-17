package dev.ngb.booking.booking.application.booking.service;

import dev.ngb.booking.booking.application.booking.exception.InvalidBookingException;
import dev.ngb.booking.booking.domain.booking.BookingSource;
import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.catalog.api.calendar.OfferingCalendar;
import dev.ngb.booking.catalog.api.calendar.SessionCalendar;
import java.time.Instant;
import java.util.UUID;

/**
 * What a booking body has to satisfy before anything is claimed: the customer reference the Tenant sent, the places of
 * a capacity session, or the range and duration of an interval offering.
 *
 * <p>The rules answer with the values a use case then decides with, so a use case never re-reads a raw request field,
 * and every refusal carries its own stable code. Which resource fills a requirement is the assignment planner's
 * question, because it depends on the group's strategy and on what is already claimed.
 */
public class BookingRules {

    private static final String CAPACITY = "CAPACITY";

    private static final int MAX_CUSTOMER_REF = 200;

    private static final int MIN_POOL_CODE = 1;

    private static final int MAX_POOL_CODE = 60;

    private static final long SECONDS_PER_MINUTE = 60L;

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
     * The range an interval booking asks for.
     *
     * @throws InvalidBookingException when the start is missing or the duration is outside the offering's range or step
     */
    public Window intervalWindow(OfferingCalendar offering, Instant start, Integer durationMinutes) {
        if (start == null) {
            throw new InvalidBookingException("start is required for an offering that sells a range");
        }
        int duration = duration(offering, durationMinutes);
        return new Window(start, start.plusSeconds(duration * SECONDS_PER_MINUTE));
    }

    /** A range to claim, before the resources that fill it are chosen. */
    public record Window(Instant start, Instant end) {}

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
}
