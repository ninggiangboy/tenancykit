package dev.ngb.loyalty.tiers.domain.policy;

import dev.ngb.loyalty.program.api.tier.TierView;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * When an order leaves the window a tier is decided over, what the orders inside it add up to, and which tier that is.
 *
 * <p>The window is a count of calendar months in the program's own zone, so "the last twelve months" means the twelve
 * months a member lived through rather than three hundred and sixty-five days. Calendar arithmetic clamps to the month
 * end: a window that starts on the 31st begins on the 28th, 29th or 30th when that month is shorter, which is what keeps
 * a leap year from moving a member's tier.
 *
 * <p>The window is half-open. An order at exactly the boundary instant no longer counts, so the instant an order ages out
 * is precisely the instant it stops counting, and that is the instant the member is looked at again.
 */
public final class TierPolicy {

    public static final int MIN_WINDOW_MONTHS = 1;
    public static final int MAX_WINDOW_MONTHS = 60;

    private final int windowMonths;
    private final ZoneId zone;

    public TierPolicy(int windowMonths, ZoneId zone) {
        if (windowMonths < MIN_WINDOW_MONTHS || windowMonths > MAX_WINDOW_MONTHS) {
            throw new IllegalArgumentException(
                    "a tier window is within " + MIN_WINDOW_MONTHS + "-" + MAX_WINDOW_MONTHS + " months");
        }
        this.windowMonths = windowMonths;
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public int windowMonths() {
        return windowMonths;
    }

    public ZoneId zone() {
        return zone;
    }

    /** The last instant outside the window: an order at or before it has aged out. */
    public Instant windowStart(Instant now) {
        return now.atZone(zone).minusMonths(windowMonths).toInstant();
    }

    /** The sum of the orders that are still inside the window at {@code now}. */
    public long qualifyingSpend(List<Spend> spend, Instant now) {
        Instant start = windowStart(now);
        long total = 0L;
        for (Spend entry : spend) {
            if (entry.occurredAt().isAfter(start)) {
                total += entry.amount();
            }
        }
        return total;
    }

    /**
     * The first instant at which an order that happened at {@code occurredAt} is out of the window, which is when the
     * member has to be looked at again.
     *
     * <p>It is not simply the order's own instant plus the window: subtracting a month from a day the shorter month does
     * not have clamps, so the day itself is a value the window never starts on and the order leaves when the window
     * enters the month after it. Everything else is the same local time on the same day, that many months on.
     */
    public Instant leavesAt(Instant occurredAt) {
        ZonedDateTime target = occurredAt.atZone(zone);
        YearMonth leaves = YearMonth.from(target).plusMonths(windowMonths);
        if (leaves.lengthOfMonth() < target.getDayOfMonth()) {
            return leaves.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        }
        return leaves.atDay(target.getDayOfMonth()).atTime(target.toLocalTime()).atZone(zone).toInstant();
    }

    /**
     * The tier the member holds at {@code now}: the highest threshold its qualifying spend reaches, the spend itself, and
     * the instant its oldest counted order ages out, which is when the answer can next change on its own.
     *
     * @param tiers the program's list, lowest first; empty when the program has none, and then there is no tier to give
     */
    public Choice choose(List<TierView> tiers, List<Spend> spend, Instant now) {
        Instant start = windowStart(now);
        long qualifying = 0L;
        Instant oldest = null;
        for (Spend entry : spend) {
            if (!entry.occurredAt().isAfter(start)) {
                continue;
            }
            qualifying += entry.amount();
            if (oldest == null || entry.occurredAt().isBefore(oldest)) {
                oldest = entry.occurredAt();
            }
        }
        TierView chosen = null;
        for (TierView tier : tiers) {
            if (tier.minSpend() <= qualifying && (chosen == null || tier.minSpend() >= chosen.minSpend())) {
                chosen = tier;
            }
        }
        Instant reviewAt = oldest == null ? null : leavesAt(oldest);
        return new Choice(chosen == null ? null : chosen.code(), qualifying, reviewAt);
    }

    /** One order as the window counts it: when it happened and the net amount it is worth after its returns. */
    public record Spend(Instant occurredAt, long amount) {

        public Spend {
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (amount < 0) {
                throw new IllegalArgumentException("an order's qualifying spend must not be negative");
            }
        }
    }

    /**
     * What the policy answers about one member.
     *
     * @param tierCode the tier the spend reaches, or null when the program has no tiers
     * @param reviewAt when the oldest counted order ages out, or null when nothing is inside the window
     */
    public record Choice(String tierCode, long qualifyingSpend, Instant reviewAt) {}
}
