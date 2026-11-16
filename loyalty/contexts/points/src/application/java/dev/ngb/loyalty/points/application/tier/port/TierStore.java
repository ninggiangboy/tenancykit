package dev.ngb.loyalty.points.application.tier.port;

import dev.ngb.loyalty.points.api.tier.MemberTiers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The member row as the tier evaluation reads and writes it: the spend the member's orders count for, the tier it
 * holds, the next instant it is looked at and the batches the daily run walks.
 *
 * <p>It is the write side's own port, so the answer the evaluation publishes is served by a service that decides nothing
 * about storage, and the storage stays in this context's adapter.
 */
public interface TierStore {

    /** The member row under a lock; the member is the first lock of every flow that writes it. */
    Optional<MemberTiers.TierMember> lock(UUID memberId);

    /** The earned orders whose instant is strictly after {@code after}, oldest first. */
    List<MemberTiers.SpendEntry> spendSince(UUID memberId, Instant after);

    void recordTier(UUID memberId, String tierCode, Instant reviewAt);

    void setReviewAt(UUID memberId, Instant reviewAt);

    List<UUID> remarkForReview(Instant at, UUID afterId, int limit);

    List<UUID> dueForReview(Instant now, UUID afterId, int limit);

    boolean holds(String tierCode);
}
