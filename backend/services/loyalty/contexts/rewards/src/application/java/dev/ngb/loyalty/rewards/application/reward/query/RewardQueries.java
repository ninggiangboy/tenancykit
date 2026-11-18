package dev.ngb.loyalty.rewards.application.reward.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.rewards.application.reward.exception.RewardRefusal;
import java.util.UUID;

/** The catalog reads: one page of it and one entry, both as the API answers them. */
public class RewardQueries {

    private final UnitOfWork unitOfWork;
    private final RewardReadRepository rewards;

    public RewardQueries(UnitOfWork unitOfWork, RewardReadRepository rewards) {
        this.unitOfWork = unitOfWork;
        this.rewards = rewards;
    }

    public Page<RewardView> list(ListQuery query, Boolean active) {
        return unitOfWork.transactional(() -> rewards.list(query, active));
    }

    public RewardView get(UUID id) {
        return unitOfWork.transactional(() -> rewards
                .findById(id)
                .orElseThrow(() -> RewardRefusal.notFound(
                        "REWARD_NOT_FOUND", "No reward with that id exists in this tenant.")));
    }
}
