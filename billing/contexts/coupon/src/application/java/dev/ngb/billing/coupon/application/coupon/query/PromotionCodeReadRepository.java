package dev.ngb.billing.coupon.application.coupon.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** The promotion-code read side. */
public interface PromotionCodeReadRepository {

    Optional<PromotionCodeView> findById(UUID id);

    Page<PromotionCodeView> list(ListQuery query, UUID couponId);
}
