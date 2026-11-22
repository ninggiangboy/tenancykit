package dev.ngb.billing.coupon.domain.coupon;

import dev.ngb.billing.sharedkernel.money.Money;
import java.util.Objects;

/**
 * How long one redemption lasts. Any mix of the three limits, and a limit that is not declared does not exist: a
 * duration with none of them runs forever, which is why the coupon cannot be created without a total bound of its
 * own (see {@link Limits}).
 *
 * <p>The application and total-discount limits are per redemption, not per coupon; the coupon's own count and budget
 * live in {@link Limits}.
 */
public record Duration(TimeWindow window, Integer maxApplications, Money maxTotalDiscount) {

    public Duration {
        if (maxApplications != null && maxApplications < 1) {
            throw new IllegalArgumentException("a redemption has at least one application");
        }
        if (maxTotalDiscount != null && !maxTotalDiscount.isPositive()) {
            throw new IllegalArgumentException("a total-discount limit must be positive");
        }
    }

    /** A redemption that never ends on its own. */
    public static Duration forever() {
        return new Duration(null, null, null);
    }

    public boolean isUnlimited() {
        return window == null && maxApplications == null && maxTotalDiscount == null;
    }
}
