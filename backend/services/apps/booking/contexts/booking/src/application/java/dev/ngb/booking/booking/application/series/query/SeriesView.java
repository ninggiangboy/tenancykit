package dev.ngb.booking.booking.application.series.query;

import dev.ngb.booking.booking.application.booking.query.BookingView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A series as the GET handlers answer it: its rule's outcome and the bookings it created. */
public record SeriesView(
        UUID id,
        UUID offeringId,
        String customerRef,
        String mode,
        String status,
        long version,
        List<BookingView> occurrences,
        List<SkippedOccurrenceView> skipped) {

    public SeriesView {
        occurrences = List.copyOf(occurrences);
        skipped = List.copyOf(skipped);
    }

    /** An occurrence the recurrence named and the series did not book because its place was taken. */
    public record SkippedOccurrenceView(Instant startsAt, String reason) {}
}
