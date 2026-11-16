package dev.ngb.loyalty.points.domain.earn;

import java.util.List;

/**
 * What a set of lines is worth: the whole number of points, the eligible net amount they were computed from, the
 * snapshot the computation used and each line's contribution.
 *
 * <p>{@code spendAmount} is what the order is worth in minor units after returns, which is also the figure a tier
 * evaluation counts.
 */
public record EarnOutcome(long points, long spendAmount, EarnSnapshot snapshot, List<LineContribution> contributions) {

    public EarnOutcome {
        if (points < 0) {
            throw new IllegalArgumentException("points must not be negative");
        }
        if (spendAmount < 0) {
            throw new IllegalArgumentException("spendAmount must not be negative");
        }
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
    }
}
