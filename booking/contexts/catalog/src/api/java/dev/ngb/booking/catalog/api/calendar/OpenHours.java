package dev.ngb.booking.catalog.api.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a resource is open for a range, resolved in the resource's own zone.
 *
 * <p>A window is local wall-clock time and a day may have 23 or 25 hours, so the instants are resolved per date rather
 * than offset from one another. An exception replaces that date's windows; a {@code CLOSED} exception empties them.
 */
public final class OpenHours {

    private static final String CLOSED = "CLOSED";
    private static final int MINUTES_PER_DAY = 1440;

    private OpenHours() {}

    public static boolean covers(ResourceCalendar calendar, Instant start, Instant end) {
        ZoneId zone = ZoneId.of(calendar.zone());
        LocalDate first = start.atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = end.atZone(zone).toLocalDate().plusDays(1);
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            for (WorkingHourWindow window : windows(calendar, date)) {
                Instant opens = instantAt(date, window.opensMinute(), zone);
                Instant closes = instantAt(date, window.closesMinute(), zone);
                if (!start.isBefore(opens) && !end.isAfter(closes)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<WorkingHourWindow> windows(ResourceCalendar calendar, LocalDate date) {
        for (CalendarExceptionRange exception : calendar.exceptions()) {
            if (date.isBefore(exception.fromDate()) || date.isAfter(exception.toDate())) {
                continue;
            }
            return CLOSED.equals(exception.kind()) ? List.of() : exception.hours();
        }
        int weekday = date.getDayOfWeek().getValue();
        List<WorkingHourWindow> weekly = new ArrayList<>();
        for (WorkingHourWindow window : calendar.hours()) {
            if (window.weekday() == weekday) {
                weekly.add(window);
            }
        }
        return weekly;
    }

    private static Instant instantAt(LocalDate date, int minute, ZoneId zone) {
        LocalDate day = minute >= MINUTES_PER_DAY ? date.plusDays(1) : date;
        int fromMidnight = minute % MINUTES_PER_DAY;
        return ZonedDateTime.of(day, LocalTime.of(fromMidnight / 60, fromMidnight % 60), zone).toInstant();
    }
}
