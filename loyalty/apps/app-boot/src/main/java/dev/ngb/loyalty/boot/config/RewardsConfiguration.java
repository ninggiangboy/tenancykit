package dev.ngb.loyalty.boot.config;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.boot.rewards.RedemptionVoucherHook;
import dev.ngb.loyalty.points.api.member.MemberDirectory;
import dev.ngb.loyalty.points.api.redemption.PointSpender;
import dev.ngb.loyalty.points.application.redemption.port.RedemptionReversalHook;
import dev.ngb.loyalty.rewards.adapter.reward.persistence.JooqRewardReadRepository;
import dev.ngb.loyalty.rewards.adapter.reward.persistence.JooqRewardRepository;
import dev.ngb.loyalty.rewards.adapter.voucher.persistence.JooqVoucherReadRepository;
import dev.ngb.loyalty.rewards.adapter.voucher.persistence.JooqVoucherRepository;
import dev.ngb.loyalty.rewards.application.reward.command.CreateRewardUseCase;
import dev.ngb.loyalty.rewards.application.reward.command.RedeemRewardUseCase;
import dev.ngb.loyalty.rewards.application.reward.command.UpdateRewardUseCase;
import dev.ngb.loyalty.rewards.application.reward.port.RewardRepository;
import dev.ngb.loyalty.rewards.application.reward.query.RewardQueries;
import dev.ngb.loyalty.rewards.application.reward.query.RewardReadRepository;
import dev.ngb.loyalty.rewards.application.voucher.command.UseVoucherUseCase;
import dev.ngb.loyalty.rewards.application.voucher.port.VoucherRepository;
import dev.ngb.loyalty.rewards.application.voucher.query.VoucherQueries;
import dev.ngb.loyalty.rewards.application.voucher.query.VoucherReadRepository;
import dev.ngb.loyalty.rewards.application.voucher.service.RedemptionVoidService;
import dev.ngb.loyalty.rewards.application.voucher.service.VoucherEvents;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the rewards context: its catalog and vouchers, the facts it announces, and the adapter that undoes a redemption's
 * voucher for the points context's reversal.
 *
 * <p>The redemption spends through the one spend function the points context publishes, so the member lock, the debt and
 * balance checks and the lot order are the same ones the plain spend takes. The reversal reaches the voucher through the
 * seam the points context declares, so neither side imports the other.
 */
@Configuration(proxyBeanMethods = false)
class RewardsConfiguration {

    @Bean
    RewardRepository rewardRepository(DSLContext dsl, TenantContext tenantContext) {
        return new JooqRewardRepository(dsl, tenantContext);
    }

    @Bean
    RewardReadRepository rewardReadRepository(DSLContext dsl) {
        return new JooqRewardReadRepository(dsl);
    }

    @Bean
    VoucherRepository voucherRepository(DSLContext dsl, TenantContext tenantContext) {
        return new JooqVoucherRepository(dsl, tenantContext);
    }

    @Bean
    VoucherReadRepository voucherReadRepository(DSLContext dsl) {
        return new JooqVoucherReadRepository(dsl);
    }

    @Bean
    VoucherEvents voucherEvents(EventPublisher publisher, IdGenerator ids) {
        return new VoucherEvents(publisher, ids);
    }

    @Bean
    CreateRewardUseCase createRewardUseCase(
            UnitOfWork unitOfWork,
            RewardRepository rewards,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new CreateRewardUseCase(unitOfWork, rewards, tenantContext, ids, clock);
    }

    @Bean
    UpdateRewardUseCase updateRewardUseCase(UnitOfWork unitOfWork, RewardRepository rewards, Clock clock) {
        return new UpdateRewardUseCase(unitOfWork, rewards, clock);
    }

    @Bean
    RewardQueries rewardQueries(UnitOfWork unitOfWork, RewardReadRepository rewards) {
        return new RewardQueries(unitOfWork, rewards);
    }

    @Bean
    RedeemRewardUseCase redeemRewardUseCase(
            UnitOfWork unitOfWork,
            RewardRepository rewards,
            VoucherRepository vouchers,
            PointSpender spender,
            VoucherEvents events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new RedeemRewardUseCase(
                unitOfWork, rewards, vouchers, spender, events, tenantContext, ids, clock);
    }

    @Bean
    UseVoucherUseCase useVoucherUseCase(
            UnitOfWork unitOfWork,
            VoucherRepository vouchers,
            VoucherEvents events,
            TenantContext tenantContext,
            Clock clock) {
        return new UseVoucherUseCase(unitOfWork, vouchers, events, tenantContext, clock);
    }

    @Bean
    VoucherQueries voucherQueries(
            UnitOfWork unitOfWork, MemberDirectory members, VoucherReadRepository vouchers) {
        return new VoucherQueries(unitOfWork, members, vouchers);
    }

    @Bean
    RedemptionVoidService redemptionVoidService(
            VoucherRepository vouchers,
            RewardRepository rewards,
            VoucherEvents events,
            TenantContext tenantContext,
            Clock clock) {
        return new RedemptionVoidService(vouchers, rewards, events, tenantContext, clock);
    }

    /** The reversal undoes a reward redemption's voucher with this, replacing the no-op the points context shipped with. */
    @Bean
    RedemptionReversalHook redemptionReversalHook(RedemptionVoidService voidService) {
        return new RedemptionVoucherHook(voidService);
    }
}
