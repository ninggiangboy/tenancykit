package dev.ngb.loyalty.rewards.application.voucher.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.rewards.application.reward.port.RewardRepository;
import dev.ngb.loyalty.rewards.application.voucher.exception.VoucherRefusal;
import dev.ngb.loyalty.rewards.application.voucher.port.VoucherRepository;
import dev.ngb.loyalty.rewards.domain.reward.RewardId;
import dev.ngb.loyalty.rewards.domain.voucher.Voucher;
import dev.ngb.loyalty.rewards.domain.voucher.VoidReason;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What a reversed redemption does to the voucher it issued: it voids it and gives its unit of stock back.
 *
 * <p>It runs inside the reversal's own transaction, after the member's row is locked and before the points go back, so a
 * refusal leaves the redemption and its voucher exactly as they were. A voucher that was already used refuses the
 * reversal: the member has had what the reward granted, and taking the points back as well would give it twice.
 *
 * <p>The voucher's row is the second lock of the flow and the reward's row the third, which is the order the redemption
 * itself takes when it writes them, so the two cannot form a cycle.
 */
public class RedemptionVoidService {

    private final VoucherRepository vouchers;
    private final RewardRepository rewards;
    private final VoucherEvents events;
    private final TenantContext tenantContext;
    private final Clock clock;

    public RedemptionVoidService(
            VoucherRepository vouchers,
            RewardRepository rewards,
            VoucherEvents events,
            TenantContext tenantContext,
            Clock clock) {
        this.vouchers = vouchers;
        this.rewards = rewards;
        this.events = events;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    /**
     * Voids the voucher a reversed redemption issued and gives one unit of stock back.
     *
     * @throws VoucherRefusal {@code VOUCHER_ALREADY_USED} when the voucher was consumed, which refuses the reversal
     */
    public void voidForReversal(UUID memberId, UUID redemptionId) {
        TenantId tenantId = tenantContext.requireTenant();
        Instant now = clock.now();
        Optional<Voucher> found = vouchers.lockByRedemption(redemptionId);
        if (found.isEmpty()) {
            // A plain spend issued nothing, so a reversal of one undoes nothing here.
            return;
        }
        Voucher voucher = found.get();
        if (!voucher.memberId().equals(memberId)) {
            throw new IllegalStateException("voucher " + voucher.id() + " belongs to another member");
        }
        switch (voucher.status()) {
            case USED -> throw VoucherRefusal.conflict(
                    "VOUCHER_ALREADY_USED", "The reward redemption issued a voucher that has already been used.");
            case VOIDED -> {
                // An erasure void is final: the stock it took was already given back by whoever voided it.
                return;
            }
            case ISSUED -> {
                voucher.voidWith(VoidReason.REVERSAL);
                vouchers.voidIssued(voucher.id(), VoidReason.REVERSAL);
                rewards.returnOne(new RewardId(voucher.rewardId()));
                events.voucherVoided(tenantId, now, voucher, VoidReason.REVERSAL);
            }
        }
    }
}
