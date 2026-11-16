package dev.ngb.billing.coupon.domain.coupon;

import dev.ngb.billing.sharedkernel.money.Money;
import java.time.Instant;

/**
 * The bounds on a coupon as a whole: how many times it may be redeemed, when redemption closes, how much may be
 * given away and how small a charge it will touch.
 *
 * <p>A coupon must declare at least one total bound — {@code redeemBy}, {@code maxRedemptions} or {@code budget} —
 * because a Tenant System may create coupons, and a coupon with no bound at all is unbounded money. The duration of
 * one redemption does not count: it limits a single wallet, not the total given away.
 */
public record Limits(
        Instant redeemBy,
        Long maxRedemptions,
        Integer maxPerCustomer,
        Money minCharge,
        Money maxDiscountPerApplication,
        Money budget) {

    public Limits {
        if (maxRedemptions != null && maxRedemptions < 1) {
            throw new IllegalArgumentException("a coupon is redeemed at least once");
        }
        if (maxPerCustomer != null && maxPerCustomer < 1) {
            throw new IllegalArgumentException("a customer may redeem at least once");
        }
        requirePositive(minCharge, "minCharge");
        requirePositive(maxDiscountPerApplication, "maxDiscountPerApplication");
        requirePositive(budget, "budget");
        if (redeemBy == null && maxRedemptions == null && budget == null) {
            throw new IllegalArgumentException("a coupon needs a total bound: redeemBy, maxRedemptions or budget");
        }
    }

    /** Unbounded in every dimension but the total bound the caller supplies. */
    public static Limits boundedBy(Instant redeemBy) {
        return new Limits(redeemBy, null, null, null, null, null);
    }

    private static void requirePositive(Money money, String name) {
        if (money != null && !money.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
