package dev.ngb.booking.catalog.domain.calendar;

/**
 * One window of a weekly pattern, in local wall-clock minutes from midnight.
 *
 * <p>Minutes rather than a time of day, because {@code 24:00} — the end of a day — is the one close the API accepts
 * that no time column can hold. A window is half-open: it opens at {@code opensMinute} and closes at
 * {@code closesMinute}, so two windows that touch at one minute do not overlap.
 *
 * @param weekday ISO weekday, 1 = Monday
 * @param opensMinute minutes from midnight, 0 to 1439
 * @param closesMinute minutes from midnight, 1 to 1440
 */
public record WorkingHour(int weekday, int opensMinute, int closesMinute) {

    public WorkingHour {
        if (weekday < 1 || weekday > 7) {
            throw new IllegalArgumentException("a weekday is 1 to 7: " + weekday);
        }
        if (opensMinute < 0 || opensMinute > 1439) {
            throw new IllegalArgumentException("an opening minute is 0 to 1439: " + opensMinute);
        }
        if (closesMinute < 1 || closesMinute > 1440) {
            throw new IllegalArgumentException("a closing minute is 1 to 1440: " + closesMinute);
        }
        if (closesMinute <= opensMinute) {
            throw new IllegalArgumentException("a window closes after it opens: " + opensMinute + " to " + closesMinute);
        }
    }
}
