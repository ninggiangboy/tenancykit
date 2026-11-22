package dev.ngb.billing.coupon.domain.discount;

import dev.ngb.billing.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one place a percentage becomes money. Percent is applied to minor units through {@link BigDecimal} so the
 * result is exact before the single rounding step, and rounded {@code HALF_UP} so a half minor unit always moves the
 * same way.
 */
final class DiscountMath {

    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

    private DiscountMath() {}

    /** {@code base × basisPoints ÷ 10,000}, rounded once to whole minor units. */
    static Money percentOf(Money base, int basisPoints) {
        BigDecimal minorUnits = BigDecimal.valueOf(base.minorUnits())
                .multiply(BigDecimal.valueOf(basisPoints))
                .divide(TEN_THOUSAND, 0, RoundingMode.HALF_UP);
        return Money.of(minorUnits.longValueExact(), base.currency());
    }

    /** The smaller of two amounts. */
    static Money min(Money first, Money second) {
        return first.isLessThan(second) ? first : second;
    }
}
