package dev.ngb.billing.coupon.domain.discount;

import dev.ngb.billing.coupon.domain.coupon.Benefit;
import dev.ngb.billing.coupon.domain.redemption.RedemptionId;
import dev.ngb.billing.sharedkernel.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What a set of redemptions takes off one charge. It is a pure function of its inputs and reads nothing, so the same
 * facts and the same candidates always give the same answer, and the order the candidates arrive in changes nothing.
 *
 * <p>The rules, in the order they matter:
 * <ol>
 *   <li>Eligibility: a redemption may take part only if it is consumable, its coupon covers the charge (a deactivated coupon still
 *       serves redemptions that are already running),
 *       and there is something left of its budget.</li>
 *   <li>Stacking: the stackable coupons run together, and each coupon that is not stackable runs alone. The
 *       combination that leaves the lowest net wins; a tie goes to the earliest redemption, then the lowest id.</li>
 *   <li>Order inside a combination: percent before fixed amount, and inside a kind the oldest redemption first.</li>
 *   <li>Caps: each discount is the smallest of what the benefit gives, the coupon's per-application maximum, the
 *       redemption's remaining total, the coupon's remaining budget and the running net. It is never negative.</li>
 * </ol>
 *
 * <p>Each percent is rounded once, to whole minor units, and every step works on what is still owed, so the lines add
 * up to gross minus net exactly.
 */
public final class DiscountPlanner {

    /** Percent coupons come first, then fixed amounts; the running net makes that order worth more than the reverse. */
    private static final Comparator<DiscountCandidate> APPLICATION_ORDER = Comparator
            .comparingInt((DiscountCandidate candidate) -> kindOrder(candidate.coupon().benefit()))
            .thenComparing(candidate -> candidate.redemption().redeemedAt())
            .thenComparing(candidate -> candidate.redemption().id());

    /** The combination that leaves the lowest net, then the one whose earliest redemption came first. */
    private static final Comparator<Combination> BY_LOWEST_NET = Comparator
            .comparing((Combination combination) -> combination.quote().net().minorUnits())
            .thenComparing(Combination::earliestRedeemedAt)
            .thenComparing(Combination::earliestId);

    private DiscountPlanner() {}

    /**
     * The best a set of redemptions can do for this charge.
     *
     * @param candidates the redemptions offered, with their coupons and remaining budgets, in any order
     * @param facts what is being charged
     * @param now the instant eligibility is judged at
     */
    public static DiscountQuote plan(List<DiscountCandidate> candidates, ChargeFacts facts, Instant now) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(now, "now");

        List<DiscountCandidate> eligible = candidates.stream()
                .filter(candidate -> Eligibility.applies(candidate, facts, now))
                .toList();
        if (eligible.isEmpty()) {
            return DiscountQuote.undiscounted(facts.gross());
        }

        return combinationsOf(eligible).stream()
                .map(members -> apply(members, facts))
                .min(BY_LOWEST_NET)
                .orElseThrow()
                .quote();
    }

    /** The stackable set together, and each coupon that refuses stacking alone. */
    private static List<List<DiscountCandidate>> combinationsOf(List<DiscountCandidate> eligible) {
        List<List<DiscountCandidate>> combinations = new ArrayList<>();
        List<DiscountCandidate> stackable = eligible.stream().filter(candidate -> candidate.coupon().stackable()).toList();
        if (!stackable.isEmpty()) {
            combinations.add(stackable);
        }
        eligible.stream()
                .filter(candidate -> !candidate.coupon().stackable())
                .forEach(candidate -> combinations.add(List.of(candidate)));
        return combinations;
    }

    /**
     * The best set of redemptions to run is chosen on the net each combination produces, so the planner evaluates every
     * combination in full. A metric-scoped coupon's base is limited to what is left of its own metrics, which is why
     * the subtotals are carried through a combination rather than recomputed from the gross.
     */
    private static Combination apply(List<DiscountCandidate> members, ChargeFacts facts) {
        List<DiscountCandidate> ordered = members.stream().sorted(APPLICATION_ORDER).toList();
        Money runningNet = facts.gross();
        MetricSubtotals subtotals = MetricSubtotals.of(facts);
        List<AppliedDiscount> lines = new ArrayList<>();
        for (DiscountCandidate candidate : ordered) {
            boolean metricScoped = candidate.coupon().scope().isMetricScoped();
            Money base = metricScoped ? subtotals.baseFor(candidate.coupon().scope().metrics(), runningNet) : runningNet;
            Money taken = discountOf(candidate, base, runningNet);
            if (!taken.isPositive()) {
                continue;
            }
            lines.add(new AppliedDiscount(candidate.redemption().id(), candidate.coupon().id(), candidate.coupon().name(), taken));
            runningNet = runningNet.minus(taken);
            if (metricScoped) {
                subtotals.reduce(candidate.coupon().scope().metrics(), taken);
            }
        }
        DiscountQuote quote = new DiscountQuote(facts.gross(), runningNet, lines);
        DiscountCandidate earliest = ordered.stream()
                .min(Comparator.comparing((DiscountCandidate candidate) -> candidate.redemption().redeemedAt())
                        .thenComparing(candidate -> candidate.redemption().id()))
                .orElseThrow();
        return new Combination(quote, earliest.redemption().redeemedAt(), earliest.redemption().id());
    }

    /** One coupon's contribution, capped by every limit that applies to it and by what is still owed. */
    private static Money discountOf(DiscountCandidate candidate, Money base, Money runningNet) {
        Money raw = switch (candidate.coupon().benefit()) {
            case Benefit.PercentOff percent -> DiscountMath.percentOf(base, percent.basisPoints());
            case Benefit.AmountOff amountOff -> DiscountMath.min(amountOff.amount(), base);
            case Benefit.WalletCredit ignored -> Money.zero(runningNet.currency());
        };

        Money cap = runningNet;
        Money perApplication = candidate.coupon().limits().maxDiscountPerApplication();
        if (perApplication != null) {
            cap = DiscountMath.min(cap, perApplication);
        }
        Money redemptionLeft = candidate.redemption().remainingTotalDiscount();
        if (redemptionLeft != null) {
            cap = DiscountMath.min(cap, redemptionLeft);
        }
        Money budgetLeft = candidate.remainingBudget();
        if (budgetLeft != null) {
            cap = DiscountMath.min(cap, budgetLeft);
        }

        Money taken = DiscountMath.min(raw, cap);
        return taken.isNegative() ? Money.zero(runningNet.currency()) : taken;
    }

    private static int kindOrder(Benefit benefit) {
        return benefit instanceof Benefit.PercentOff ? 0 : 1;
    }

    /** A combination's result, with the earliest redemption that took part, so ties resolve the same way every time. */
    private record Combination(DiscountQuote quote, Instant earliestRedeemedAt, RedemptionId earliestId) {}
}
