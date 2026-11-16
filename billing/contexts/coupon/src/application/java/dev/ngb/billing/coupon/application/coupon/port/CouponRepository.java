package dev.ngb.billing.coupon.application.coupon.port;

import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import java.util.Optional;

/**
 * Coupons are created once and never edited: the only write is the one that records a deactivation and the one that
 * advances the coupon-wide counters. A redemption asks this port for the coupon under its row lock when the coupon
 * carries a budget, so two charges against different wallets cannot over-spend it.
 */
public interface CouponRepository {

    void add(Coupon coupon);

    /** Writes the terms and the deactivation, never the counters, which the conditional statements own. */
    void update(Coupon coupon);

    Optional<Coupon> findById(CouponId id);

    /** Reads and locks the coupon until the transaction ends. */
    Optional<Coupon> lockById(CouponId id);

    /**
     * Counts one redemption against {@code maxRedemptions} in one statement, so a burst of redeems cannot exceed it.
     *
     * @return whether a row changed, which is false when the coupon has reached its redemption count
     */
    boolean countRedemption(CouponId id);

    /**
     * Spends up to {@code amount} of the coupon's budget in one statement, never below zero.
     *
     * @return the amount actually spent, in minor units; 0 when the budget is already gone
     */
    long spendBudget(CouponId id, long amount);
}
