package dev.ngb.billing.coupon.application.coupon.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** The coupon read side: one page at a time, straight into a view, never through the aggregate. */
public interface CouponReadRepository {

    Optional<CouponView> findById(UUID id);

    Page<CouponView> list(ListQuery query);
}
