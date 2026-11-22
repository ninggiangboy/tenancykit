package dev.ngb.booking.catalog.application.calendar.service;

import dev.ngb.booking.catalog.application.catalog.exception.CatalogValidationException;
import dev.ngb.booking.catalog.domain.calendar.WorkingHour;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The rules a calendar's wall-clock hours follow before they are stored: how {@code HH:mm} and {@code 24:00} become
 * minutes from midnight, and that no two windows of one weekday overlap.
 *
 * <p>Minutes rather than a time of day, because {@code 24:00} — the end of a day — is the one close the API accepts
 * that no time column can hold. The rules live in one place, so the weekly pattern and a dated exception cannot
 * disagree about what a window is.
 */
public final class CalendarRules {

    /** The minute one past the last of a day, which the API writes as {@code 24:00}. */
    public static final int MINUTES_PER_DAY = 1440;

    private static final String BAD_TIME = "A time is HH:mm between 00:00 and 23:59, or 24:00.";

    private CalendarRules() {}

    /** @return {@code HH:mm} or {@code 24:00} as minutes from midnight, so {@code 24:00} is 1440 */
    public static int minutes(String text) {
        if (text == null) {
            throw new CatalogValidationException("A time is required.");
        }
        String value = text.strip();
        if (value.equals("24:00")) {
            return MINUTES_PER_DAY;
        }
        if (value.length() != 5 || value.charAt(2) != ':') {
            throw new CatalogValidationException(BAD_TIME);
        }
        int hour = twoDigits(value, 0);
        int minute = twoDigits(value, 3);
        if (hour > 23 || minute > 59) {
            throw new CatalogValidationException(BAD_TIME);
        }
        return hour * 60 + minute;
    }

    /** @return a minute from midnight as {@code HH:mm}, with {@code 24:00} for the end of a day */
    public static String text(int minute) {
        if (minute == MINUTES_PER_DAY) {
            return "24:00";
        }
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    /** One window, parsed and checked, ready to be stored. */
    public static WorkingHour workingHour(int weekday, String opens, String closes) {
        if (weekday < 1 || weekday > 7) {
            throw new CatalogValidationException("A weekday is 1 (Monday) to 7 (Sunday).");
        }
        int opensMinute = minutes(opens);
        int closesMinute = minutes(closes);
        if (opensMinute > MINUTES_PER_DAY - 1) {
            throw new CatalogValidationException("A window opens between 00:00 and 23:59.");
        }
        if (closesMinute <= opensMinute) {
            throw new CatalogValidationException("A window closes after it opens.");
        }
        return new WorkingHour(weekday, opensMinute, closesMinute);
    }

    /**
     * The whole pattern, checked so no two windows of one weekday overlap.
     *
     * @return the given windows, in the order they were sent
     */
    public static List<WorkingHour> weekly(List<WorkingHour> hours) {
        List<WorkingHour> sorted = new ArrayList<>(hours);
        sorted.sort(Comparator.comparingInt(WorkingHour::weekday).thenComparingInt(WorkingHour::opensMinute));
        WorkingHour previous = null;
        for (WorkingHour hour : sorted) {
            if (previous != null
                    && previous.weekday() == hour.weekday()
                    && hour.opensMinute() < previous.closesMinute()) {
                throw new CatalogValidationException("Two windows of weekday " + hour.weekday() + " overlap.");
            }
            previous = hour;
        }
        return List.copyOf(hours);
    }

    private static int twoDigits(String value, int at) {
        char high = value.charAt(at);
        char low = value.charAt(at + 1);
        if (high < '0' || high > '9' || low < '0' || low > '9') {
            throw new CatalogValidationException(BAD_TIME);
        }
        return (high - '0') * 10 + (low - '0');
    }
}
