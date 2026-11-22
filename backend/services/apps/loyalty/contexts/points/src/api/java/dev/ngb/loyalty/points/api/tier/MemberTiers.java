package dev.ngb.loyalty.points.api.tier;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the tier evaluation needs of a member: the spend its orders earned over a window, the tier it holds and the next
 * moment it has to be looked at again.
 *
 * <p>It is the one door onto the member row's tier columns, so the context that decides a tier writes it here and never
 * touches {@code members} itself. Every method runs inside the caller's transaction: the evaluation owns the commit and
 * the caller takes the member's row lock first, so a rise on an earn and a fall on the daily run cannot interleave.
 */
public interface MemberTiers {

    /** The member row under a lock, or empty when no such member exists in this tenant. */
    Optional<TierMember> lock(UUID memberId);

    /**
     * The member's orders that earned, newest spend last, whose {@code occurredAt} is strictly after {@code after}: the
     * boundary is exclusive so that an order ages out exactly when its review instant arrives.
     */
    List<SpendEntry> spendSince(UUID memberId, Instant after);

    /** Writes the tier the member holds and the instant it is next looked at, in one update. */
    void recordTier(UUID memberId, String tierCode, Instant reviewAt);

    /** Moves the next review instant, leaving the tier alone. */
    void setReviewAt(UUID memberId, Instant reviewAt);

    /**
     * Moves up to {@code limit} members after {@code afterId}, in id order, to the review instant {@code at}: a tier
     * change marks every member this way, and a member already at or before {@code at} is left where it is, so a run
     * that stopped halfway resumes instead of repeating what it did.
     *
     * @return the ids this batch stepped over, in order, so the caller can continue after the last of them
     */
    List<UUID> remarkForReview(Instant at, UUID afterId, int limit);

    /** The members whose review is due, in id order after {@code afterId}, at most {@code limit}. */
    List<UUID> dueForReview(Instant now, UUID afterId, int limit);

    /** Whether any live member of this tenant holds {@code tierCode}, which decides whether it may leave the list. */
    boolean holds(String tierCode);

    /** A member as the evaluation reads it: its identity, the tier it holds and the instant it is next looked at. */
    record TierMember(UUID memberId, String externalRef, String tierCode, Instant tierReviewAt) {}

    /** One earned order as the window sees it: when it happened and the net amount it counts for. */
    record SpendEntry(Instant occurredAt, long amount) {}
}
