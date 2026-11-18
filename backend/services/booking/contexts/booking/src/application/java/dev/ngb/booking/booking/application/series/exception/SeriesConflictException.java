package dev.ngb.booking.booking.application.series.exception;

import dev.ngb.booking.booking.application.booking.exception.BookingProblem;
import java.time.Instant;
import java.util.List;

/**
 * At least one occurrence of an all-or-nothing series could not be booked, so none was. The body lists each failing
 * occurrence and the reason, which is what lets a caller retry only the ones that matter.
 */
public class SeriesConflictException extends BookingProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "series-conflict";

    private final transient List<Conflict> conflicts;

    public SeriesConflictException(String message, List<Conflict> conflicts) {
        super(CODE, message);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<Conflict> conflicts() {
        return conflicts;
    }

    /** One occurrence the recurrence named and the series could not book. */
    public record Conflict(Instant startsAt, String reason) {}
}
