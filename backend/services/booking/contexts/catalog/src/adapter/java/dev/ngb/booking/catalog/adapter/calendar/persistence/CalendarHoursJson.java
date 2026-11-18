package dev.ngb.booking.catalog.adapter.calendar.persistence;

import dev.ngb.booking.catalog.application.calendar.service.CalendarRules;
import dev.ngb.booking.catalog.domain.calendar.WorkingHour;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stored {@code hours} document of a dated exception: the array of windows an {@code OPEN} exception replaces its
 * range with, in the local wall-clock shape the migration names.
 *
 * <p>The writer emits the fields in one order, so the same windows always give the same bytes; the reader accepts any
 * order, so a document written by another tool still reads.
 */
public final class CalendarHoursJson {

    private static final Pattern OBJECT = Pattern.compile("\\{[^{}]*}");
    private static final Pattern WEEKDAY = Pattern.compile("\"weekday\"\\s*:\\s*(\\d+)");
    private static final Pattern OPENS = Pattern.compile("\"opens\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CLOSES = Pattern.compile("\"closes\"\\s*:\\s*\"([^\"]*)\"");

    private CalendarHoursJson() {}

    public static String write(List<WorkingHour> hours) {
        StringBuilder document = new StringBuilder("[");
        for (int i = 0; i < hours.size(); i++) {
            WorkingHour hour = hours.get(i);
            if (i > 0) {
                document.append(',');
            }
            document.append("{\"weekday\":")
                    .append(hour.weekday())
                    .append(",\"opens\":\"")
                    .append(CalendarRules.text(hour.opensMinute()))
                    .append("\",\"closes\":\"")
                    .append(CalendarRules.text(hour.closesMinute()))
                    .append("\"}");
        }
        return document.append(']').toString();
    }

    public static List<WorkingHour> read(String document) {
        List<WorkingHour> hours = new ArrayList<>();
        if (document == null || document.isBlank()) {
            return hours;
        }
        Matcher objects = OBJECT.matcher(document);
        while (objects.find()) {
            String object = objects.group();
            hours.add(new WorkingHour(
                    number(WEEKDAY, object),
                    CalendarRules.minutes(quoted(OPENS, object)),
                    CalendarRules.minutes(quoted(CLOSES, object))));
        }
        return List.copyOf(hours);
    }

    private static int number(Pattern pattern, String object) {
        Matcher matcher = pattern.matcher(object);
        if (!matcher.find()) {
            throw new IllegalStateException("a stored calendar hour has no weekday: " + object);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static String quoted(Pattern pattern, String object) {
        Matcher matcher = pattern.matcher(object);
        if (!matcher.find()) {
            throw new IllegalStateException("a stored calendar hour is incomplete: " + object);
        }
        return matcher.group(1);
    }
}
