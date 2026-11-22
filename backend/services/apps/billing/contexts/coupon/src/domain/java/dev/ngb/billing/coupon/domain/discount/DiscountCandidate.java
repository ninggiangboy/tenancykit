package dev.ngb.billing.coupon.domain.discount;

import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.billing.sharedkernel.money.Money;
import java.util.Objects;

/**
 * A redemption offered to the engine, with its coupon and with what is left of the coupon's budget. The budget is an
 * input rather than something the engine reads, so the planner stays a pure function: the caller takes the locks and
 * knows the number.
 *
 * <p>{@code remainingBudget} is {@code null} when the coupon has no budget, in which case it never runs out.
 */
public record DiscountCandidate(Coupon coupon, Redemption redemption, Money remainingBudget) {

    public DiscountCandidate {
        Objects.requireNonNull(coupon, "coupon");
        Objects.requireNonNull(redemption, "redemption");
        if (!coupon.id().equals(redemption.couponId())) {
            throw new IllegalArgumentException("the redemption is of another coupon");
        }
        if (!coupon.currency().equals(redemption.currency())) {
            throw new IllegalArgumentException("the coupon and its redemption are in different currencies");
        }
        if (remainingBudget != null && !remainingBudget.currency().equals(coupon.currency())) {
            throw new IllegalArgumentException("currency mismatch: coupon is %s, budget is %s".formatted(coupon.currency(), remainingBudget.currency()));
        }
    }
}
