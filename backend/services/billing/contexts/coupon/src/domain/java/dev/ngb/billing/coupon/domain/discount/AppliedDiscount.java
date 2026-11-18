package dev.ngb.billing.coupon.domain.discount;

import dev.ngb.billing.coupon.domain.coupon.Benefit;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.redemption.RedemptionId;
import dev.ngb.billing.sharedkernel.money.Money;

import java.util.List;
import java.util.Objects;

/** One coupon's contribution to one charge: what was taken off and by which redemption, so the Charge explains itself. */
public record AppliedDiscount(RedemptionId redemptionId, CouponId couponId, String couponName, Money amount) {

    public AppliedDiscount {
        Objects.requireNonNull(redemptionId, "redemptionId");
        Objects.requireNonNull(couponId, "couponId");
        Objects.requireNonNull(couponName, "couponName");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("an applied discount is positive: a coupon that gives nothing is not a line");
        }
    }
}
