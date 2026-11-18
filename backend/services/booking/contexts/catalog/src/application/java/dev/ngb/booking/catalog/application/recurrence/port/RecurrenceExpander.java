package dev.ngb.booking.catalog.application.recurrence.port;

import java.time.Instant;
import java.util.List;

/**
 * Expands iCalendar recurrence content lines into the start instants of a window, so nothing above this port knows
 * which library or routine produced them.
 *
 * <p>The lines are read in {@code zone}: a start inside a spring-forward gap moves forward by the gap and one inside a
 * fall-back overlap takes the first occurrence, exactly as RFC 5545 says. The window is inclusive of {@code from} and
 * exclusive of {@code to}, and the answer is ascending.
 */
public interface RecurrenceExpander {

    List<Instant> expand(String zone, String dtstartLocal, List<String> ruleLines, Instant from, Instant to);
}
