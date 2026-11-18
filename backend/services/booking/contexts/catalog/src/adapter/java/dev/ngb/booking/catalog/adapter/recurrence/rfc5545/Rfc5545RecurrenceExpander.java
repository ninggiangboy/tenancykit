package dev.ngb.booking.catalog.adapter.recurrence.rfc5545;

import dev.ngb.booking.catalog.application.recurrence.port.RecurrenceExpander;
import dev.ngb.booking.catalog.application.recurrence.service.RecurrenceValidator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.dmfs.rfc5545.DateTime;
import org.dmfs.rfc5545.recur.InvalidRecurrenceRuleException;
import org.dmfs.rfc5545.recur.RecurrenceRule;
import org.dmfs.rfc5545.recur.RecurrenceRuleIterator;

/**
 * Expands iCalendar content lines with {@code lib-recur}, which owns the RFC 5545 rule grammar: nothing here parses a
 * {@code FREQ}, a {@code BYDAY} or a {@code BYSETPOS}.
 *
 * <p>The library yields the intended wall-clock start of each occurrence; this adapter resolves that local date-time in
 * the resource's zone itself, so the JDK decides what a spring-forward gap or a fall-back overlap means. A nonexistent
 * local time moves forward by the gap and an ambiguous one takes its first occurrence, exactly as RFC 5545 says, and a
 * library's own offset guess cannot disagree.
 *
 * <p>{@code EXDATE} and {@code RDATE} are applied around the rule: an exclusion is removed and an addition is kept even
 * when it sits outside the rule's own bound. This class is the only place that names the library, so replacing it
 * changes nothing above {@link RecurrenceExpander}.
 */
public class Rfc5545RecurrenceExpander implements RecurrenceExpander {

    /** One past the cap the validator enforces, so a rule beyond it is a rejection rather than a truncation. */
    private static final int MAX_OCCURRENCES = RecurrenceValidator.MAX_OCCURRENCES + 1;

    private static final DateTimeFormatter BASIC_SECONDS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);

    @Override
    public List<Instant> expand(String zone, String dtstartLocal, List<String> ruleLines, Instant from, Instant to) {
        ZoneId zoneId = RecurrenceDateValues.zone(zone);
        LocalDateTime dtstart = RecurrenceDateValues.local(dtstartLocal);
        String ruleValue = null;
        List<Instant> additions = new ArrayList<>();
        List<Instant> exclusions = new ArrayList<>();
        for (String raw : ruleLines) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String line = raw.strip();
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("the line " + line + " is not NAME:VALUE");
            }
            String name = line.substring(0, colon);
            String value = line.substring(colon + 1);
            int semicolon = name.indexOf(';');
            Map<String, String> parameters =
                    RecurrenceDateValues.parameters(semicolon < 0 ? null : name.substring(semicolon + 1));
            String property = (semicolon < 0 ? name : name.substring(0, semicolon)).trim().toUpperCase(Locale.ROOT);
            switch (property) {
                case "RRULE" -> {
                    if (ruleValue != null) {
                        throw new IllegalArgumentException("a recurrence carries one RRULE line");
                    }
                    ruleValue = value;
                }
                case "EXDATE" -> exclusions.addAll(RecurrenceDateValues.instants(value, parameters, zoneId));
                case "RDATE" -> additions.addAll(RecurrenceDateValues.instants(value, parameters, zoneId));
                default -> throw new IllegalArgumentException(property + " is not a line this service expands");
            }
        }
        if (ruleValue == null) {
            throw new IllegalArgumentException("a recurrence carries one RRULE line");
        }
        RecurrenceRule rule = parse(ruleValue);
        DateTime start = DateTime.parse(zoneId.getId(), dtstart.format(BASIC_SECONDS));
        RecurrenceRuleIterator occurrences = rule.iterator(start);
        TreeSet<Instant> instants = new TreeSet<>();
        while (occurrences.hasNext()) {
            DateTime occurrence = occurrences.nextDateTime();
            LocalDateTime local = LocalDateTime.of(
                    occurrence.getYear(),
                    occurrence.getMonth() + 1,
                    occurrence.getDayOfMonth(),
                    occurrence.getHours(),
                    occurrence.getMinutes(),
                    occurrence.getSeconds());
            Instant instant = local.atZone(zoneId).toInstant();
            if (to != null && !instant.isBefore(to)) {
                break;
            }
            if (from == null || !instant.isBefore(from)) {
                instants.add(instant);
            }
            if (instants.size() > MAX_OCCURRENCES) {
                break;
            }
        }
        instants.removeAll(exclusions);
        instants.addAll(additions);
        List<Instant> result = new ArrayList<>(instants.size());
        for (Instant instant : instants) {
            if (from != null && instant.isBefore(from)) {
                continue;
            }
            if (to != null && !instant.isBefore(to)) {
                continue;
            }
            result.add(instant);
        }
        return List.copyOf(result);
    }

    private static RecurrenceRule parse(String value) {
        try {
            return new RecurrenceRule(value);
        } catch (InvalidRecurrenceRuleException invalid) {
            throw new IllegalArgumentException("the rule " + value + " cannot be read: " + invalid.getMessage(), invalid);
        }
    }
}
