package dev.ngb.billing.coupon.domain.coupon;

/**
 * A coupon is ACTIVE from creation until it is deactivated, and deactivation is final: terms are immutable and a
 * deactivated coupon never accepts a new redemption again. Redemptions already running are unaffected unless the
 * deactivation revokes them.
 */
public enum CouponStatus {
    ACTIVE,
    INACTIVE
}
