package dev.ngb.billing.coupon.application.coupon.port;

import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.PromotionCode;
import dev.ngb.billing.coupon.domain.coupon.PromotionCodeId;
import java.util.List;
import java.util.Optional;

/** Promotion codes, addressed by their text as a customer quotes it or by their id. */
public interface PromotionCodeRepository {

    void add(PromotionCode code);

    /** Writes a code's deactivation; the redeemed count belongs to {@link #countRedemption}. */
    void update(PromotionCode code);

    Optional<PromotionCode> findById(PromotionCodeId id);

    /** Looks a code up by its normalized text within the tenant. Takes no lock. */
    Optional<PromotionCode> findByCode(String code);

    /** Reads and locks a code by its normalized text until the transaction ends. */
    Optional<PromotionCode> lockByCode(String code);

    List<PromotionCode> findByCoupon(CouponId couponId);

    /**
     * Counts one redemption against the code's own limit in one statement.
     *
     * @return whether a row changed, which is false when the code has reached its limit
     */
    boolean countRedemption(PromotionCodeId id);
}
