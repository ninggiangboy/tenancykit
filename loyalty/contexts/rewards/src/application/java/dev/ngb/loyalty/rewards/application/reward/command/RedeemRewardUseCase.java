package dev.ngb.loyalty.rewards.application.reward.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.api.redemption.PointSpender;
import dev.ngb.loyalty.rewards.application.reward.exception.RewardRefusal;
import dev.ngb.loyalty.rewards.application.reward.port.RewardRepository;
import dev.ngb.loyalty.rewards.application.reward.service.RewardRules;
import dev.ngb.loyalty.rewards.application.voucher.port.VoucherRepository;
import dev.ngb.loyalty.rewards.application.voucher.service.VoucherEvents;
import dev.ngb.loyalty.rewards.domain.reward.Reward;
import dev.ngb.loyalty.rewards.domain.reward.RewardId;
import dev.ngb.loyalty.rewards.domain.voucher.Voucher;
import dev.ngb.loyalty.rewards.domain.voucher.VoucherCode;
import dev.ngb.loyalty.rewards.domain.voucher.VoucherId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Exchanges a member's points for a reward and issues its single-use voucher, in one transaction.
 *
 * <p>The order of the steps is the order of the locks. The reward is read without a lock, so it costs nobody anything;
 * the spend then takes the member's row lock and all of its checks, and only after that the reward's stock is taken. The
 * conditional decrement matches {@code stock > 0}, so two redemptions of the last unit cannot both succeed: the loser
 * takes no row and fails the whole transaction, which gives the member's points back with it.
 *
 * <p>A key that already issued a voucher replays that voucher and announces nothing, because nothing moved. A key whose
 * redemption was a plain spend is a conflict rather than a second voucher.
 */
public class RedeemRewardUseCase {

    /** One redemption as the caller asks for it: the member, the reward and the caller's key for this redemption. */
    public record Command(String externalRef, String idempotencyKey, RewardId rewardId) {}

    /** The redemption, the voucher it issued and the balance the spend left. */
    public record Result(
            java.util.UUID redemptionId,
            RewardId rewardId,
            Voucher voucher,
            long available,
            long debt,
            Instant createdAt,
            boolean replayed) {}

    /** The longest key a caller may send, which is the header's own documented bound. */
    private static final int MAX_KEY = 200;

    /** How many codes are tried before a collision is treated as a fault rather than as bad luck. */
    private static final int CODE_ATTEMPTS = 5;

    private final UnitOfWork unitOfWork;
    private final RewardRepository rewards;
    private final VoucherRepository vouchers;
    private final PointSpender spender;
    private final VoucherEvents events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public RedeemRewardUseCase(
            UnitOfWork unitOfWork,
            RewardRepository rewards,
            VoucherRepository vouchers,
            PointSpender spender,
            VoucherEvents events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.rewards = rewards;
        this.vouchers = vouchers;
        this.spender = spender;
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command) {
        String idempotencyKey = key(command.idempotencyKey());
        if (command.rewardId() == null) {
            throw RewardRefusal.invalid("rewardId is required.");
        }
        RewardId rewardId = command.rewardId();
        return unitOfWork.transactional(() -> redeem(command.externalRef(), idempotencyKey, rewardId));
    }

    private Result redeem(String externalRef, String idempotencyKey, RewardId rewardId) {
        TenantId tenantId = tenantContext.requireTenant();
        Instant now = clock.now();
        Reward reward = rewards
                .findById(rewardId)
                .orElseThrow(() -> RewardRefusal.notFound(
                        "REWARD_NOT_FOUND", "No reward with that id exists in this tenant."));
        RewardRules.requireRedeemable(reward, now);

        PointSpender.Receipt receipt = spender.spend(
                new PointSpender.Command(externalRef, idempotencyKey, reward.pointsCost(), "REWARD " + reward.name()));
        if (receipt.replayed()) {
            return replay(tenantId, receipt, rewardId);
        }
        if (reward.stock() != null && !rewards.takeOne(rewardId)) {
            // No row changed, so the whole unit of work unwinds and the spend goes back with it.
            throw RewardRefusal.conflict("OUT_OF_STOCK", "The catalog holds no unit of this reward left.");
        }
        Voucher voucher = issue(tenantId, reward, receipt.memberId(), receipt.externalRef(), receipt.redemptionId(), now);
        events.voucherIssued(tenantId, now, voucher);
        return new Result(receipt.redemptionId(), rewardId, voucher, receipt.available(), receipt.debt(),
                receipt.createdAt(), false);
    }

    /**
     * The voucher this key issued before, which is the same voucher however often the caller sends the key.
     *
     * <p>The key names the reward the caller asks for, so a key whose stored voucher was issued for another reward is
     * another request, not a replay: two catalog entries may carry the same name and cost, and the spend the key wrote
     * cannot tell them apart. Answering the stored voucher here would hand the caller a voucher for a reward it did not
     * name, so the mismatch is a conflict.
     */
    private Result replay(TenantId tenantId, PointSpender.Receipt receipt, RewardId rewardId) {
        Voucher voucher = vouchers.findByRedemption(receipt.redemptionId()).orElseThrow(() -> RewardRefusal.conflict(
                "IDEMPOTENCY_CONFLICT",
                "The same Idempotency-Key was sent before for a plain spend, not for a reward."));
        if (!voucher.rewardId().equals(rewardId.value())) {
            throw RewardRefusal.conflict(
                    "IDEMPOTENCY_CONFLICT", "The same Idempotency-Key was sent before for another reward.");
        }
        return new Result(receipt.redemptionId(), rewardId, voucher, receipt.available(), receipt.debt(),
                receipt.createdAt(), true);
    }

    /** Writes the voucher, taking a fresh code and trying again when the tenant already holds that one. */
    private Voucher issue(
            TenantId tenantId,
            Reward reward,
            java.util.UUID memberId,
            String externalRef,
            java.util.UUID redemptionId,
            Instant now) {
        Instant expiresAt = now.plus(reward.voucherValidDays(), ChronoUnit.DAYS);
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            Voucher voucher = Voucher.issued(
                    tenantId,
                    new VoucherId(ids.newId()),
                    VoucherCode.generated(),
                    reward.id().value(),
                    memberId,
                    redemptionId,
                    externalRef,
                    now,
                    expiresAt);
            if (vouchers.add(voucher)) {
                return voucher;
            }
        }
        throw new IllegalStateException("no free voucher code after " + CODE_ATTEMPTS + " attempts");
    }

    /** A redemption without a key could spend twice, so the key is required and never corrected. */
    private static String key(String raw) {
        if (raw == null || raw.isBlank()) {
            throw RewardRefusal.invalid("IDEMPOTENCY_KEY_REQUIRED", "An Idempotency-Key header is required to redeem.");
        }
        String value = raw.strip();
        if (value.length() > MAX_KEY) {
            throw RewardRefusal.invalid(
                    "IDEMPOTENCY_KEY_INVALID", "The Idempotency-Key has at most " + MAX_KEY + " characters.");
        }
        return value;
    }
}
