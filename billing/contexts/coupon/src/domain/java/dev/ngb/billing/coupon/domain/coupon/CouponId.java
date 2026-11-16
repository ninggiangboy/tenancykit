package dev.ngb.billing.coupon.domain.coupon;

import java.util.Objects;
import java.util.UUID;

/** Identifier of a Coupon. A value object so a raw UUID is never threaded through business code. */
public record CouponId(UUID value) {

    public CouponId {
        Objects.requireNonNull(value, "value");
    }

    public static CouponId of(String text) {
        return new CouponId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
