package dev.ngb.loyalty.points.application.member.query;

/**
 * A member read with the tier it holds and the one above it, as the program's tier list describes them today.
 *
 * <p>The tier is a summary rather than the program's own view, so a read model is shaped for the caller that reads it.
 */
public record MemberDetail(MemberView member, TierSummary tier, TierSummary nextTier) {

    /** The part of a tier a member read shows: its code, its name, where it starts and what it multiplies. */
    public record TierSummary(String code, String name, long minSpend, java.math.BigDecimal earnMultiplier) {}
}
