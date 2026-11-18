package dev.ngb.loyalty.rewards.application.reward.port;

import dev.ngb.loyalty.rewards.domain.reward.Reward;
import dev.ngb.loyalty.rewards.domain.reward.RewardId;
import java.util.Optional;

/** The current tenant's catalog entries, as the command side writes them. */
public interface RewardRepository {

    void add(Reward reward);

    /** Writes the merged reward; the caller has already validated it and holds its row. */
    void update(Reward reward);

    /** Reads a reward without a lock, for a redemption that only needs its cost and its window. */
    Optional<Reward> findById(RewardId id);

    /** Reads a reward under a row lock, so a patch cannot race another one. */
    Optional<Reward> lockById(RewardId id);

    /**
     * Takes one unit of stock, and only one: the update matches the reward and asks for {@code stock > 0}, so two
     * redemptions of the last unit cannot both succeed.
     *
     * @return true when this call took the unit, or when the reward holds no stock column to take it from
     */
    boolean takeOne(RewardId id);

    /** Gives one unit of stock back, for a redemption that was reversed; an unlimited reward is left alone. */
    void returnOne(RewardId id);
}
