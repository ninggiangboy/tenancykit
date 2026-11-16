package dev.ngb.loyalty.points.application.member.query;

import java.math.BigDecimal;

/**
 * A member read with the tier it holds and the one above it, as the program's tier list describes them today.
 *
 * <p>The tier is a summary rather than the program's own view, so a read model is shaped for the caller that reads it.
 * {@code remainingSpend} is the spend still missing for that tier: it is null on the tier the member holds, because the
 * member has reached it already.
 */
public record MemberDetail(MemberView member, TierSummary tier, TierSummary nextTier) {

    /** The part of a tier a member read shows: where it starts, what it multiplies and how far away it is. */
    public record TierSummary(
            String code, String name, long minSpend, BigDecimal earnMultiplier, Long remainingSpend) {}
}
