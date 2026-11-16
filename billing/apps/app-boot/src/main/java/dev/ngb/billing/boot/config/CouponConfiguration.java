package dev.ngb.billing.boot.config;

import dev.ngb.billing.coupon.adapter.coupon.persistence.JooqCouponReadRepository;
import dev.ngb.billing.coupon.adapter.coupon.persistence.JooqCouponRepository;
import dev.ngb.billing.coupon.adapter.coupon.persistence.JooqPromotionCodeReadRepository;
import dev.ngb.billing.coupon.adapter.coupon.persistence.JooqPromotionCodeRepository;
import dev.ngb.billing.coupon.adapter.redemption.persistence.JooqRedemptionReadRepository;
import dev.ngb.billing.coupon.adapter.redemption.persistence.JooqRedemptionRepository;
import dev.ngb.billing.coupon.application.coupon.port.CouponRepository;
import dev.ngb.billing.coupon.application.coupon.port.PromotionCodeRepository;
import dev.ngb.billing.coupon.application.coupon.query.CouponReadRepository;
import dev.ngb.billing.coupon.application.coupon.query.PromotionCodeReadRepository;
import dev.ngb.billing.coupon.application.redemption.port.RedemptionRepository;
import dev.ngb.billing.coupon.application.redemption.query.RedemptionReadRepository;
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
}
