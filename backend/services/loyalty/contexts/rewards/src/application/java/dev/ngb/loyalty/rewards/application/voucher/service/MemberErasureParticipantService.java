package dev.ngb.loyalty.rewards.application.voucher.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.api.member.MemberErasureParticipant;
import dev.ngb.loyalty.rewards.application.reward.port.RewardRepository;
import dev.ngb.loyalty.rewards.application.voucher.port.VoucherRepository;
import dev.ngb.loyalty.rewards.domain.reward.RewardId;
import dev.ngb.loyalty.rewards.domain.voucher.Voucher;
import dev.ngb.loyalty.rewards.domain.voucher.VoidReason;
import java.time.Instant;
import java.util.UUID;

/**
 * What an erasure does to the vouchers a member still holds: each {@code ISSUED} one is voided with the reason
 * {@code ERASURE}, its unit of stock goes back to the reward it came from, and one {@code voucher.voided} announces it.
 *
 * <p>It runs inside the erasure's own transaction, after the member's row is locked, and it takes the voucher rows and then
 * the reward rows: the same order a reward redemption and a reversal take them in, so the three cannot form a cycle. A used
 * or already voided voucher is left as it is — the member had what the reward granted, and an erasure is not a reversal.
 */
public class MemberErasureParticipantService implements MemberErasureParticipant {

    private final VoucherRepository vouchers;
    private final RewardRepository rewards;
    private final VoucherEvents events;
    private final TenantContext tenantContext;
    private final Clock clock;

    public MemberErasureParticipantService(
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

    @Override
    public int erase(UUID memberId) {
        TenantId tenantId = tenantContext.requireTenant();
        Instant now = clock.now();
        int voided = 0;
        for (Voucher voucher : vouchers.lockIssuedOfMember(memberId)) {
            voucher.voidWith(VoidReason.ERASURE);
            vouchers.voidForErasure(voucher.id());
            rewards.returnOne(new RewardId(voucher.rewardId()));
            events.voucherVoided(tenantId, now, voucher, VoidReason.ERASURE);
            voided++;
        }
        return voided;
    }

    @Override
    public int erasedRecords(UUID memberId) {
        return (int) vouchers.countVoidedForErasure(memberId);
    }
}
