package dev.ngb.billing.coupon.application.coupon.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The promotion-code read side. */
public interface PromotionCodeReadRepository {

    Optional<PromotionCodeView> findById(UUID id);

    List<PromotionCodeView> findByCoupon(UUID couponId);
}
