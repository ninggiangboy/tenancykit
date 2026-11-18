package dev.ngb.loyalty.rewards.application.reward.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.loyalty.rewards.application.reward.exception.RewardRefusal;
import dev.ngb.loyalty.rewards.application.reward.port.RewardRepository;
import dev.ngb.loyalty.rewards.application.reward.service.RewardRules;
import dev.ngb.loyalty.rewards.domain.reward.Reward;
import dev.ngb.loyalty.rewards.domain.reward.RewardId;
import dev.ngb.loyalty.rewards.domain.reward.RewardStatus;
import java.time.Instant;

/**
 * Changes the fields a patch names and leaves the rest as they are. The merged reward is validated as a whole, so a patch
 * cannot move a window past its other end or drop the voucher lifetime below a day.
 *
 * <p>A cost or stock change never reaches a voucher already issued: a voucher keeps the expiry its own issue chose.
 */
public class UpdateRewardUseCase {

    /** A null field is left as it is; {@code active} is the wire shorthand for the stored status. */
    public record Command(
            String name,
            String description,
            Long pointsCost,
            Long stock,
            Boolean active,
            String status,
            Instant validFrom,
            Instant validTo,
            Integer voucherValidDays) {}

    private final UnitOfWork unitOfWork;
    private final RewardRepository rewards;
    private final Clock clock;

    public UpdateRewardUseCase(UnitOfWork unitOfWork, RewardRepository rewards, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.rewards = rewards;
        this.clock = clock;
    }

    public Reward execute(RewardId id, Command change, ExpectedVersion expected) {
        return unitOfWork.transactional(() -> {
            Reward reward = rewards
                    .lockById(id)
                    .orElseThrow(() -> RewardRefusal.notFound(
                            "REWARD_NOT_FOUND", "No reward with that id exists in this tenant."));
            expected.check(reward.version());

            String name = change.name() == null ? reward.name() : RewardRules.name(change.name());
            String description =
                    change.description() == null ? reward.description() : RewardRules.description(change.description());
            long pointsCost =
                    change.pointsCost() == null ? reward.pointsCost() : RewardRules.pointsCost(change.pointsCost());
            Long stock = change.stock() == null ? reward.stock() : RewardRules.stock(change.stock());
            RewardStatus status = change.status() == null && change.active() == null
                    ? reward.status()
                    : RewardRules.status(change.status(), change.active());
            Instant[] window = RewardRules.window(
                    change.validFrom() == null ? reward.validFrom() : change.validFrom(),
                    change.validTo() == null ? reward.validTo() : change.validTo());
            int voucherValidDays = change.voucherValidDays() == null
                    ? reward.voucherValidDays()
                    : RewardRules.voucherValidDays(change.voucherValidDays());

            reward.reconfigure(
                    name, description, pointsCost, stock, status, window[0], window[1], voucherValidDays, clock.now());
            rewards.update(reward);
            return reward;
        });
    }
}
