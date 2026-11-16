package dev.ngb.billing.coupon.domain.discount;

import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import java.time.Instant;

/**
 * Whether a redemption may take something off one charge. Every test here is a fact the engine can see without
 * reading anything: the redemption's own state, the coupon's terms and the charge's facts.
 */
final class Eligibility {

    private Eligibility() {}

    static boolean applies(DiscountCandidate candidate, ChargeFacts facts, Instant now) {
        Coupon coupon = candidate.coupon();
        Redemption redemption = candidate.redemption();
        if (!redemption.isConsumable(now)) {
            return false;
        }
        if (!coupon.isActive()) {
            return false;
        }
        if (!coupon.currency().equals(facts.gross().currency())) {
            return false;
        }
        if (coupon.limits().minCharge() != null && facts.gross().isLessThan(coupon.limits().minCharge())) {
            return false;
        }
        if (!matchesKind(coupon, facts)) {
            return false;
        }
        if (coupon.scope().isPlanScoped() && (facts.planId() == null || !coupon.scope().planIds().contains(facts.planId()))) {
            return false;
        }
        if (coupon.scope().isMetricScoped() && !coupon.scope().metrics().stream().anyMatch(facts.metricAmounts()::containsKey)) {
            return false;
        }
        return candidate.remainingBudget() == null || candidate.remainingBudget().isPositive();
    }

    private static boolean matchesKind(Coupon coupon, ChargeFacts facts) {
        return coupon.scope().matchesAnyKind() || coupon.scope().kinds().contains(facts.kind());
    }
}
