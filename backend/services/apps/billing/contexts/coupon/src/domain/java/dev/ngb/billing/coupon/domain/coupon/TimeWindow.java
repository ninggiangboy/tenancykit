package dev.ngb.billing.coupon.domain.coupon;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * How long a redemption runs, counted from the moment it was redeemed. A window is days or calendar months: months
 * are how a "valid for one month" promotion is actually meant, and a day count would land on the wrong date across a
 * month boundary.
 *
 * <p>The clock is UTC throughout Billing, so a window is computed in UTC and its end is an instant like any other.
 */
public record TimeWindow(int count, Unit unit) {

    public enum Unit {
        DAYS,
        MONTHS
    }

    public TimeWindow {
        Objects.requireNonNull(unit, "unit");
        if (count < 1) {
            throw new IllegalArgumentException("a time window is at least one unit long");
        }
    }

    public static TimeWindow days(int count) {
        return new TimeWindow(count, Unit.DAYS);
    }

    public static TimeWindow months(int count) {
        return new TimeWindow(count, Unit.MONTHS);
    }

    /** The instant the window closes for a redemption made at {@code redeemedAt}. */
    public Instant endsAt(Instant redeemedAt) {
        Objects.requireNonNull(redeemedAt, "redeemedAt");
        ZonedDateTime start = redeemedAt.atZone(ZoneOffset.UTC);
        return switch (unit) {
            case DAYS -> start.plusDays(count).toInstant();
            case MONTHS -> start.plusMonths(count).toInstant();
        };
    }
}
