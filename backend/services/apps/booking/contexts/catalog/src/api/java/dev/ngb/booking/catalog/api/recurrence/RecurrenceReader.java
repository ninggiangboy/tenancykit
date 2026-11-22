package dev.ngb.booking.catalog.api.recurrence;

import java.time.Instant;
import java.util.List;

/**
 * The catalog's recurrence expansion for another context: the instants a rule names inside a window, in the zone it was
 * written in.
 *
 * <p>It answers instants rather than local times, so a booking's series books the same local time across a DST change
 * without doing the arithmetic itself. A rule the catalog will not expand is a {@link RecurrenceException}.
 */
public interface RecurrenceReader {

    List<Instant> expand(String zone, String dtstart, List<String> rule, Instant from, Instant to);
}
