package dev.ngb.billing.coupon.domain.coupon;

import java.util.UUID;

/** The id of a promotion code that a redemption was made through. */
public record PromotionCodeId(UUID value) implements Comparable<PromotionCodeId> {

    public PromotionCodeId {
        java.util.Objects.requireNonNull(value, "value");
    }

    public static PromotionCodeId of(String text) {
        return new PromotionCodeId(UUID.fromString(text));
    }

    @Override
    public int compareTo(PromotionCodeId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
