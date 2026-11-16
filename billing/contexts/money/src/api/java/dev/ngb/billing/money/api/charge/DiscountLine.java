package dev.ngb.billing.money.api.charge;

import dev.ngb.billing.sharedkernel.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * One coupon's contribution to one charge, as the Charge records it so the arithmetic reads top to bottom. The
 * identifiers are plain UUIDs because money owns this record and names no type from the coupon context.
 */
public record DiscountLine(UUID redemptionId, UUID couponId, String couponName, String benefitType, Money amount) {

    public DiscountLine {
        Objects.requireNonNull(redemptionId, "redemptionId");
        Objects.requireNonNull(couponId, "couponId");
        Objects.requireNonNull(couponName, "couponName");
        Objects.requireNonNull(benefitType, "benefitType");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a recorded discount is positive");
        }
    }
}
