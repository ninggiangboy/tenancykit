package dev.ngb.loyalty.points.application.tier.service;

import dev.ngb.loyalty.points.api.tier.MemberTiers;
import dev.ngb.loyalty.points.application.tier.port.TierStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers the tier evaluation's questions about a member by reading and writing the member row through {@link TierStore}.
 *
 * <p>It opens no transaction of its own: the evaluation owns the commit, so a rise commits with the earn that caused it
 * and a fall commits on its own.
 */
public class MemberTiersService implements MemberTiers {

    private final TierStore store;

    public MemberTiersService(TierStore store) {
        this.store = store;
    }

    @Override
    public Optional<TierMember> lock(UUID memberId) {
        return store.lock(memberId);
    }

    @Override
    public List<SpendEntry> spendSince(UUID memberId, Instant after) {
        return store.spendSince(memberId, after);
    }

    @Override
    public void recordTier(UUID memberId, String tierCode, Instant reviewAt) {
        store.recordTier(memberId, tierCode, reviewAt);
    }

    @Override
    public void setReviewAt(UUID memberId, Instant reviewAt) {
        store.setReviewAt(memberId, reviewAt);
    }

    @Override
    public List<UUID> remarkForReview(Instant at, UUID afterId, int limit) {
        return store.remarkForReview(at, afterId, limit);
    }

    @Override
    public List<UUID> dueForReview(Instant now, UUID afterId, int limit) {
        return store.dueForReview(now, afterId, limit);
    }

    @Override
    public boolean holds(String tierCode) {
        return store.holds(tierCode);
    }
}
