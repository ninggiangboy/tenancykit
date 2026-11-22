package dev.ngb.loyalty.rewards.application.reward.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.rewards.application.reward.port.RewardRepository;
import dev.ngb.loyalty.rewards.application.reward.service.RewardRules;
import dev.ngb.loyalty.rewards.domain.reward.Reward;
import dev.ngb.loyalty.rewards.domain.reward.RewardId;
import dev.ngb.loyalty.rewards.domain.reward.RewardStatus;
import java.time.Instant;

/** Adds one entry to the Tenant's catalog. */
public class CreateRewardUseCase {

    /** A new reward: its name and cost are required, everything else defaults or is absent. */
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
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateRewardUseCase(
            UnitOfWork unitOfWork,
            RewardRepository rewards,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.rewards = rewards;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Reward execute(Command command) {
        String name = RewardRules.name(command.name());
        String description = RewardRules.description(command.description());
        long pointsCost = RewardRules.pointsCost(command.pointsCost());
        Long stock = RewardRules.stock(command.stock());
        RewardStatus status = RewardRules.status(command.status(), command.active());
        Instant[] window = RewardRules.window(command.validFrom(), command.validTo());
        int voucherValidDays = RewardRules.voucherValidDays(command.voucherValidDays());
        return unitOfWork.transactional(() -> {
            TenantId tenantId = tenantContext.requireTenant();
            Reward created = Reward.create(
                    tenantId,
                    new RewardId(ids.newId()),
                    name,
                    description,
                    pointsCost,
                    stock,
                    status,
                    window[0],
                    window[1],
                    voucherValidDays,
                    clock.now());
            rewards.add(created);
            return created;
        });
    }
}
