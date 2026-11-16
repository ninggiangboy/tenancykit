package dev.ngb.booking.catalog.application.recurrence.service;

import dev.ngb.booking.catalog.application.recurrence.exception.InvalidRecurrenceException;
import dev.ngb.booking.catalog.application.recurrence.port.RecurrenceExpander;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rules a recurrence must satisfy before this service stores it or expands it.
 *
 * <p>An unparsable line, a line that is not one this service knows, a rule without its {@code RRULE}, a zone the JDK
 * does not know, a rule that needs a window and has none, and a rule that expands to more than the per-call cap are
 * all a {@code 422 invalid-recurrence} naming the offending part. A session series additionally refuses a
 * {@code FREQ} finer than daily, because a session is a thing a Tenant sells and a place claimed per minute is not.
 *
 * <p>The cap is checked by expanding a bounded probe window from the first occurrence rather than by trusting the
 * rule's own {@code COUNT}: a rule with neither {@code COUNT} nor {@code UNTIL} is otherwise unbounded, and the
 * window a caller sent is the only thing that ends it.
 */
public final class RecurrenceValidator {

    /** How many occurrences one call expands. */
    public static final int MAX_OCCURRENCES = 10_000;

    /** How far the probe window reaches from the first occurrence, which bounds what the check expands. */
    private static final int PROBE_DAYS = 366;

    private static final List<String> FINER_THAN_DAILY = List.of("SECONDLY", "MINUTELY", "HOURLY");

    private final RecurrenceExpander expander;

    public RecurrenceValidator(RecurrenceExpander expander) {
        this.expander = expander;
    }

    /** Expands a rule for a block or a preview, which may be finer than daily. */
    public List<Instant> expand(String zone, String dtstartLocal, List<String> ruleLines, Instant from, Instant to) {
        checkLines(ruleLines);
        return expandChecked(zone, dtstartLocal, ruleLines, from, to);
    }

    /** Expands a session series' rule, which may not be finer than daily. */
    public List<Instant> expandSessions(
            String zone, String dtstartLocal, List<String> ruleLines, Instant from, Instant to) {
        checkLines(ruleLines);
        rejectFinerThanDaily(ruleLines);
        return expandChecked(zone, dtstartLocal, ruleLines, from, to);
    }

    /**
     * Rejects a rule this service will not expand without answering instants.
     *
     * @throws InvalidRecurrenceException when a line cannot be read, the rule is finer than daily, or it expands past
     *     the per-call cap inside the probe window
     */
    public void check(String zone, String dtstartLocal, List<String> ruleLines) {
        checkLines(ruleLines);
        LocalDateTime start = parseLocal(dtstartLocal);
        ZoneId zoneId = zone(zone);
        Instant from = start.atZone(zoneId).toInstant();
        expand(zone, dtstartLocal, ruleLines, from, from.plusSeconds(PROBE_DAYS * 86_400L));
    }

    /**
     * Rejects a rule that a session series may not carry.
     *
     * @throws InvalidRecurrenceException when the rule repeats more often than once a day
     */
    public static void rejectFinerThanDaily(List<String> ruleLines) {
        for (String line : ruleLines) {
            String text = strip(line);
            if (!text.toUpperCase(Locale.ROOT).startsWith("RRULE")) {
                continue;
            }
            String value = text.substring(text.indexOf(':') + 1).toUpperCase(Locale.ROOT);
            for (String part : value.split(";")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2 && pair[0].trim().equals("FREQ") && FINER_THAN_DAILY.contains(pair[1].trim())) {
                    throw new InvalidRecurrenceException(
                            "FREQ=" + pair[1].trim() + " is finer than daily and cannot repeat a session series");
                }
            }
        }
    }

    private List<Instant> expandChecked(
            String zone, String dtstartLocal, List<String> ruleLines, Instant from, Instant to) {
        if (from == null || to == null) {
            throw new InvalidRecurrenceException("a recurrence is expanded inside a window and this one has none");
        }
        if (!from.isBefore(to)) {
            throw new InvalidRecurrenceException("the window ends before it starts");
        }
        parseLocal(dtstartLocal);
        zone(zone);
        List<Instant> instants;
        try {
            instants = expander.expand(zone, dtstartLocal, ruleLines, from, to);
        } catch (InvalidRecurrenceException invalid) {
            throw invalid;
        } catch (RuntimeException unreadable) {
            throw new InvalidRecurrenceException(messageOf(unreadable));
        }
        if (instants.size() > MAX_OCCURRENCES) {
            throw new InvalidRecurrenceException(
                    "the rule expands to more than the " + MAX_OCCURRENCES + " occurrences one call allows");
        }
        return instants;
    }

    private static void checkLines(List<String> ruleLines) {
        if (ruleLines == null || ruleLines.isEmpty()) {
            throw new InvalidRecurrenceException("a recurrence carries at least one line");
        }
        List<String> lines = new ArrayList<>();
        for (String line : ruleLines) {
            lines.add(strip(line));
        }
        if (lines.stream().noneMatch(text -> text.toUpperCase(Locale.ROOT).startsWith("RRULE"))) {
            throw new InvalidRecurrenceException("a recurrence carries one RRULE line");
        }
    }

    private static String strip(String line) {
        if (line == null || line.isBlank()) {
            throw new InvalidRecurrenceException("a recurrence line is not blank");
        }
        return line.strip();
    }

    private static LocalDateTime parseLocal(String dtstartLocal) {
        if (dtstartLocal == null || dtstartLocal.isBlank()) {
            throw new InvalidRecurrenceException("dtstart is required");
        }
        String text = dtstartLocal.strip();
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException notIso) {
            throw new InvalidRecurrenceException("dtstart is a local date-time, such as 2026-01-05T09:00:00");
        }
    }

    private static ZoneId zone(String zone) {
        if (zone == null || zone.isBlank()) {
            throw new InvalidRecurrenceException("zone is required");
        }
        try {
            return ZoneId.of(zone.strip());
        } catch (DateTimeException unknown) {
            throw new InvalidRecurrenceException("the zone " + zone.strip() + " is not one the JDK knows");
        }
    }

    private static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "the rule cannot be read" : message;
    }
}
