package dev.ngb.billing.coupon.application.coupon.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The coupon read side: one page at a time, straight into a view, never through the aggregate. */
public interface CouponReadRepository {

    Optional<CouponView> findById(UUID id);

    /** @param status a coupon status name, or null for every status */
    List<CouponView> list(String status, int limit, int offset);
}
