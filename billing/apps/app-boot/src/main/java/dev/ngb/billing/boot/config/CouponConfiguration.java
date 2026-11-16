package dev.ngb.billing.boot.config;

import dev.ngb.billing.coupon.adapter.coupon.persistence.JooqCouponReadRepository;
import dev.ngb.billing.coupon.adapter.coupon.persistence.JooqCouponRepository;
import dev.ngb.billing.coupon.adapter.coupon.persistence.JooqPromotionCodeReadRepository;
import dev.ngb.billing.coupon.adapter.coupon.persistence.JooqPromotionCodeRepository;
import dev.ngb.billing.coupon.adapter.coupon.web.CouponController;
import dev.ngb.billing.coupon.adapter.redemption.persistence.JooqRedemptionReadRepository;
import dev.ngb.billing.coupon.adapter.redemption.persistence.JooqRedemptionRepository;
import dev.ngb.billing.coupon.adapter.redemption.web.RedemptionController;
import dev.ngb.billing.coupon.application.coupon.command.CreateCouponUseCase;
import dev.ngb.billing.coupon.application.coupon.command.DeactivateCouponUseCase;
import dev.ngb.billing.coupon.application.coupon.command.ManagePromotionCodeUseCase;
import dev.ngb.billing.coupon.application.coupon.port.CouponRepository;
import dev.ngb.billing.coupon.application.coupon.port.PromotionCodeRepository;
import dev.ngb.billing.coupon.application.coupon.query.CouponQueries;
import dev.ngb.billing.coupon.application.coupon.query.CouponReadRepository;
import dev.ngb.billing.coupon.application.coupon.query.PromotionCodeReadRepository;
import dev.ngb.billing.coupon.application.redemption.command.RedeemCouponUseCase;
import dev.ngb.billing.coupon.application.redemption.command.RevokeRedemptionUseCase;
import dev.ngb.billing.coupon.application.redemption.port.RedemptionRepository;
import dev.ngb.billing.coupon.application.redemption.query.RedemptionReadRepository;
import dev.ngb.billing.coupon.application.redemption.service.EndingRedemptions;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.money.api.wallet.CreditWallet;
import dev.ngb.billing.money.api.wallet.WalletLookup;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.tenancy.TenantContext;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the coupon module. Holds no business rules. */
@Configuration(proxyBeanMethods = false)
class CouponConfiguration {

    @Bean
    CouponRepository couponRepository(DSLContext dsl) {
        return new JooqCouponRepository(dsl);
    }

    @Bean
    PromotionCodeRepository promotionCodeRepository(DSLContext dsl) {
        return new JooqPromotionCodeRepository(dsl);
    }

    @Bean
    RedemptionRepository redemptionRepository(DSLContext dsl) {
        return new JooqRedemptionRepository(dsl);
    }

    @Bean
    CouponReadRepository couponReadRepository(DSLContext dsl) {
        return new JooqCouponReadRepository(dsl);
    }

    @Bean
    PromotionCodeReadRepository promotionCodeReadRepository(DSLContext dsl) {
        return new JooqPromotionCodeReadRepository(dsl);
    }

    @Bean
    RedemptionReadRepository redemptionReadRepository(DSLContext dsl) {
        return new JooqRedemptionReadRepository(dsl);
    }

    @Bean
    CouponQueries couponQueries(
            UnitOfWork unitOfWork, CouponReadRepository coupons, PromotionCodeReadRepository codes, RedemptionReadRepository redemptions) {
        return new CouponQueries(unitOfWork, coupons, codes, redemptions);
    }

    @Bean
    EndingRedemptions endingRedemptions(RedemptionRepository redemptions, EventPublisher events, IdGenerator ids) {
        return new EndingRedemptions(redemptions, events, ids);
    }

    @Bean
    CreateCouponUseCase createCouponUseCase(
            UnitOfWork unitOfWork, CouponRepository coupons, PromotionCodeRepository codes, AuditLog auditLog, TenantContext tenantContext,
            IdGenerator ids, Clock clock) {
        return new CreateCouponUseCase(unitOfWork, coupons, codes, auditLog, tenantContext, ids, clock);
    }

    @Bean
    DeactivateCouponUseCase deactivateCouponUseCase(
            UnitOfWork unitOfWork, CouponRepository coupons, PromotionCodeRepository codes, EndingRedemptions endingRedemptions,
            AuditLog auditLog, Clock clock) {
        return new DeactivateCouponUseCase(unitOfWork, coupons, codes, endingRedemptions, auditLog, clock);
    }

    @Bean
    ManagePromotionCodeUseCase managePromotionCodeUseCase(
            UnitOfWork unitOfWork, CouponRepository coupons, PromotionCodeRepository codes, AuditLog auditLog, IdGenerator ids, Clock clock) {
        return new ManagePromotionCodeUseCase(unitOfWork, coupons, codes, auditLog, ids, clock);
    }

    @Bean
    RedeemCouponUseCase redeemCouponUseCase(
            UnitOfWork unitOfWork, WalletLookup wallets, CouponRepository coupons, PromotionCodeRepository codes,
            RedemptionRepository redemptions, CreditWallet creditWallet, CustomerLookup customers, EventPublisher events, AuditLog auditLog,
            TenantContext tenantContext, IdGenerator ids, Clock clock) {
        return new RedeemCouponUseCase(unitOfWork, wallets, coupons, codes, redemptions, creditWallet, customers, events, auditLog,
                tenantContext, ids, clock);
    }

    @Bean
    RevokeRedemptionUseCase revokeRedemptionUseCase(
            UnitOfWork unitOfWork, RedemptionRepository redemptions, EndingRedemptions endingRedemptions, CustomerLookup customers,
            AuditLog auditLog, Clock clock) {
        return new RevokeRedemptionUseCase(unitOfWork, redemptions, endingRedemptions, customers, auditLog, clock);
    }

    @Bean
    CouponController couponController(
            CreateCouponUseCase createCoupon, DeactivateCouponUseCase deactivateCoupon, ManagePromotionCodeUseCase manageCodes,
            CouponQueries queries, CurrentActor currentActor) {
        return new CouponController(createCoupon, deactivateCoupon, manageCodes, queries, currentActor);
    }

    @Bean
    RedemptionController redemptionController(
            RedeemCouponUseCase redeemCoupon, RevokeRedemptionUseCase revokeRedemption, CouponQueries queries, CurrentActor currentActor) {
        return new RedemptionController(redeemCoupon, revokeRedemption, queries, currentActor);
    }
}
