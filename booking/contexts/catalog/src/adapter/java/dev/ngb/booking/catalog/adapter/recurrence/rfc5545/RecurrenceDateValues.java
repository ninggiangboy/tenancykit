package dev.ngb.booking.catalog.adapter.recurrence.rfc5545;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The iCalendar date and date-time values an {@code UNTIL}, {@code EXDATE} or {@code RDATE} line carries.
 *
 * <p>A value ending in {@code Z} is a UTC instant, a value with a {@code TZID} is wall-clock in that zone, and a bare
 * value is wall-clock in the zone the caller expands in. A date alone is the start of that day in that zone.
 */
final class RecurrenceDateValues {

    private static final DateTimeFormatter BASIC_SECONDS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);
    private static final DateTimeFormatter BASIC_MINUTES = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm", Locale.ROOT);
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    private RecurrenceDateValues() {}

    /** A local date-time as an API sends it, extended ISO-8601 and without an offset. */
    static LocalDateTime local(String text) {
        String value = text.trim();
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException notIso) {
            return basic(value);
        }
    }

    /** A bare date or date-time in iCalendar's basic form, such as {@code 20260203T090000}. */
    static LocalDateTime basic(String text) {
        String value = text.trim();
        try {
            if (value.length() == 8) {
                return LocalDate.parse(value, BASIC_DATE).atStartOfDay();
            }
            if (value.length() == 13) {
                return LocalDateTime.parse(value, BASIC_MINUTES).withSecond(0);
            }
            if (value.length() == 15) {
                return LocalDateTime.parse(value, BASIC_SECONDS);
            }
        } catch (DateTimeParseException unreadable) {
            throw new IllegalArgumentException("the date " + value + " is not YYYYMMDD or YYYYMMDDTHHMMSS");
        }
        throw new IllegalArgumentException("the date " + value + " is not YYYYMMDD or YYYYMMDDTHHMMSS");
    }

    /** Every instant one {@code EXDATE} or {@code RDATE} line names, read in the zone the line or the caller gives. */
    static List<Instant> instants(String value, Map<String, String> parameters, ZoneId fallbackZone) {
        String tzid = parameters.get("TZID");
        ZoneId zone = tzid == null ? fallbackZone : zone(tzid);
        List<Instant> instants = new ArrayList<>();
        for (String part : value.split(",")) {
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.endsWith("Z") || text.endsWith("z")) {
                instants.add(basic(text.substring(0, text.length() - 1)).toInstant(ZoneOffset.UTC));
            } else {
                instants.add(basic(text).atZone(zone).toInstant());
            }
        }
        return instants;
    }

    /** The parameters of a property name, such as the {@code TZID} of {@code EXDATE;TZID=Europe/Berlin}. */
    static Map<String, String> parameters(String text) {
        Map<String, String> parameters = new HashMap<>();
        if (text == null || text.isBlank()) {
            return parameters;
        }
        for (String part : text.split(";")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) {
                parameters.put(pair[0].trim().toUpperCase(Locale.ROOT), pair[1].trim());
            }
        }
        return parameters;
    }

    static ZoneId zone(String text) {
        try {
            return ZoneId.of(text.trim());
        } catch (RuntimeException unknown) {
            throw new IllegalArgumentException("the zone " + text.trim() + " is not one the JDK knows");
        }
    }
}
