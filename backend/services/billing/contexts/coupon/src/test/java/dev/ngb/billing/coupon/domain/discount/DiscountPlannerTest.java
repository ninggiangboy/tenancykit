package dev.ngb.billing.coupon.domain.discount;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.coupon.domain.coupon.Benefit;
import dev.ngb.billing.coupon.domain.coupon.ChargeKind;
import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.Duration;
import dev.ngb.billing.coupon.domain.coupon.Limits;
import dev.ngb.billing.coupon.domain.coupon.PromotionCodeId;
import dev.ngb.billing.coupon.domain.coupon.Scope;
import dev.ngb.billing.coupon.domain.redemption.EndReason;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.billing.coupon.domain.redemption.RedemptionId;
import dev.ngb.billing.coupon.domain.redemption.RedemptionStatus;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rule table from the plan, one golden case each, so a change to the engine is a change to a documented case. */
class DiscountPlannerTest {

    private static final Currency USD = Currency.of("USD");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private static final Instant REDEEMED = NOW.minusSeconds(3_600);
    private static final CustomerId CUSTOMER = new CustomerId(UUID.randomUUID());
    private static final WalletId WALLET = new WalletId(UUID.randomUUID());
    private static final Limits OPEN = new Limits(NOW.plusSeconds(86_400), null, null, null, null, null);

    @Test
    void twentyPercentThenTenThousandOffTakesThirtyThousand() {
        DiscountQuote quote = plan(List.of(
                candidate(coupon(new Benefit.PercentOff(2_000), Scope.everything(), OPEN, true), null),
                candidate(coupon(new Benefit.AmountOff(Money.of(10_000, USD)), Scope.everything(), OPEN, true), null)));

        assertThat(quote.net()).isEqualTo(Money.of(70_000, USD));
        assertThat(quote.discountTotal()).isEqualTo(Money.of(30_000, USD));
        assertThat(quote.lines()).extracting(line -> line.amount().minorUnits()).containsExactly(20_000L, 10_000L);
    }

    @Test
    void theOrderTheCandidatesArriveInChangesNothing() {
        DiscountCandidate save20 = candidate(coupon(new Benefit.PercentOff(2_000), Scope.everything(), OPEN, true), null);
        DiscountCandidate welcome = candidate(coupon(new Benefit.AmountOff(Money.of(10_000, USD)), Scope.everything(), OPEN, true), null);

        assertThat(plan(List.of(welcome, save20)).net()).isEqualTo(plan(List.of(save20, welcome)).net()).isEqualTo(Money.of(70_000, USD));
    }

    @Test
    void aHundredPercentCouponLeavesNothingToPay() {
        DiscountQuote quote = plan(List.of(candidate(coupon(new Benefit.PercentOff(10_000), Scope.everything(), OPEN, true), null)));

        assertThat(quote.net().isZero()).isTrue();
        assertThat(quote.isFullyDiscounted()).isTrue();
    }

    @Test
    void aFixedAmountLargerThanTheChargeIsCappedAtTheCharge() {
        DiscountQuote quote = plan(List.of(candidate(coupon(new Benefit.AmountOff(Money.of(500_000, USD)), Scope.everything(), OPEN, true), null)),
                Money.of(25_000, USD));

        assertThat(quote.net().isZero()).isTrue();
        assertThat(quote.lines()).singleElement().satisfies(line -> assertThat(line.amount()).isEqualTo(Money.of(25_000, USD)));
    }

    @Test
    void aPercentIsRoundedOnceToWholeMinorUnits() {
        DiscountQuote quote = plan(List.of(candidate(coupon(new Benefit.PercentOff(333), Scope.everything(), OPEN, true), null)),
                Money.of(101, USD));

        assertThat(quote.lines()).singleElement().satisfies(line -> assertThat(line.amount().minorUnits()).isEqualTo(3L));
        assertThat(quote.net().minorUnits()).isEqualTo(98L);
    }

    @Test
    void roundingIsHalfUp() {
        DiscountQuote quote = plan(List.of(candidate(coupon(new Benefit.PercentOff(5_000), Scope.everything(), OPEN, true), null)),
                Money.of(3, USD));

        assertThat(quote.lines()).singleElement().satisfies(line -> assertThat(line.amount().minorUnits()).isEqualTo(2L));
    }

    @Test
    void aPerApplicationMaximumCapsOneApplication() {
        Limits capped = new Limits(NOW.plusSeconds(86_400), null, null, null, Money.of(5_000, USD), null);

        DiscountQuote quote = plan(List.of(candidate(coupon(new Benefit.PercentOff(2_000), Scope.everything(), capped, true), null)));

        assertThat(quote.net()).isEqualTo(Money.of(95_000, USD));
    }

    @Test
    void theRedemptionsRemainingTotalCapsWhatIsLeft() {
        Coupon coupon = coupon(new Benefit.PercentOff(2_000), Scope.everything(), OPEN, true);
        Redemption partlyUsed = Redemption.restore(new RedemptionId(UUID.randomUUID()), TENANT, coupon.id(), null, WALLET, CUSTOMER, null,
                Money.of(5_000, USD), REDEEMED, null, "customer", new ReplayIdentity("k", "h"), USD, RedemptionStatus.ACTIVE, 1,
                Money.of(3_000, USD), null, null);

        DiscountQuote quote = plan(List.of(new DiscountCandidate(coupon, partlyUsed, null)));

        assertThat(quote.net()).isEqualTo(Money.of(98_000, USD));
    }

    @Test
    void theCouponsRemainingBudgetCapsTheDiscount() {
        Coupon coupon = coupon(new Benefit.PercentOff(2_000), Scope.everything(), OPEN, true);

        DiscountQuote quote = plan(List.of(new DiscountCandidate(coupon, active(coupon, null), Money.of(1_200, USD))));

        assertThat(quote.lines()).singleElement().satisfies(line -> assertThat(line.amount()).isEqualTo(Money.of(1_200, USD)));
    }

    @Test
    void anExhaustedBudgetTakesPartInNothing() {
        Coupon coupon = coupon(new Benefit.PercentOff(2_000), Scope.everything(), OPEN, true);

        DiscountQuote quote = plan(List.of(new DiscountCandidate(coupon, active(coupon, null), Money.zero(USD))));

        assertThat(quote.isDiscounted()).isFalse();
    }

    @Test
    void aChargeBelowTheCouponsMinimumIsNotDiscounted() {
        Limits minimum = new Limits(NOW.plusSeconds(86_400), null, null, Money.of(100_000, USD), null, null);

        DiscountQuote quote = plan(List.of(candidate(coupon(new Benefit.PercentOff(2_000), Scope.everything(), minimum, true), null)),
                Money.of(99_999, USD));

        assertThat(quote.isDiscounted()).isFalse();
        assertThat(quote.net()).isEqualTo(Money.of(99_999, USD));
    }

    @Test
    void aNonStackableCouponRunsAloneAndTheLowerNetWins() {
        Coupon stackable = coupon(new Benefit.PercentOff(1_000), Scope.everything(), OPEN, true);
        Coupon exclusive = coupon(new Benefit.PercentOff(2_000), Scope.everything(), OPEN, false);

        DiscountQuote quote = plan(List.of(candidate(stackable, null), candidate(exclusive, null)));

        assertThat(quote.lines()).singleElement().satisfies(line -> assertThat(line.couponId()).isEqualTo(exclusive.id()));
        assertThat(quote.net()).isEqualTo(Money.of(80_000, USD));
    }

    @Test
    void twoStackableCouponsTogetherCanBeatOneExclusive() {
        Coupon five = coupon(new Benefit.PercentOff(500), Scope.everything(), OPEN, true);
        Coupon ten = coupon(new Benefit.PercentOff(1_000), Scope.everything(), OPEN, true);
        Coupon exclusiveTen = coupon(new Benefit.PercentOff(1_000), Scope.everything(), OPEN, false);

        DiscountQuote quote = plan(List.of(candidate(five, null), candidate(ten, null), candidate(exclusiveTen, null)));

        assertThat(quote.lines()).as("5% then 10% on the running net takes 14.5%, more than 10% alone").hasSize(2);
        assertThat(quote.net()).isEqualTo(Money.of(85_500, USD));
    }

    @Test
    void aTieBetweenCombinationsGoesToTheEarliestRedemption() {
        Coupon early = coupon(new Benefit.AmountOff(Money.of(10_000, USD)), Scope.everything(), OPEN, false);
        Coupon late = coupon(new Benefit.AmountOff(Money.of(10_000, USD)), Scope.everything(), OPEN, false);
        DiscountCandidate earlyCandidate = new DiscountCandidate(early, activeAt(early, REDEEMED), null);
        DiscountCandidate lateCandidate = new DiscountCandidate(late, activeAt(late, REDEEMED.plusSeconds(60)), null);

        DiscountQuote quote = plan(List.of(lateCandidate, earlyCandidate));

        assertThat(quote.lines()).singleElement().satisfies(line -> assertThat(line.couponId()).isEqualTo(early.id()));
    }

    @Test
    void anExpiredRedemptionTakesPartInNothing() {
        Coupon coupon = coupon(new Benefit.PercentOff(2_000), Scope.everything(), OPEN, true);
        Redemption expired = Redemption.activate(new RedemptionId(UUID.randomUUID()), TENANT, coupon.id(), null, WALLET, CUSTOMER, null, null,
                NOW.minusSeconds(1), "customer", new ReplayIdentity("k", "h"), USD, REDEEMED);

        assertThat(plan(List.of(new DiscountCandidate(coupon, expired, null))).isDiscounted()).isFalse();
    }

    @Test
    void anEndedRedemptionTakesPartInNothing() {
        Coupon coupon = coupon(new Benefit.PercentOff(2_000), Scope.everything(), OPEN, true);
        Redemption ended = active(coupon, null);
        ended.end(EndReason.EXHAUSTED, NOW);

        assertThat(plan(List.of(new DiscountCandidate(coupon, ended, null))).isDiscounted()).isFalse();
    }

    @Test
    void aDeactivatedCouponStillServesItsRunningRedemption() {
        Coupon coupon = coupon(new Benefit.PercentOff(2_000), Scope.everything(), OPEN, true);
        Redemption redemption = active(coupon, null);
        coupon.deactivate(NOW);

        assertThat(plan(List.of(new DiscountCandidate(coupon, redemption, null))).isDiscounted()).isTrue();
    }

    @Test
    void aCouponScopedToAnotherKindDoesNotApply() {
        Scope usageOnly = new Scope(Set.of(ChargeKind.USAGE), Set.of(), Set.of());

        assertThat(plan(List.of(candidate(coupon(new Benefit.PercentOff(2_000), usageOnly, OPEN, true), null))).isDiscounted()).isFalse();
    }

    @Test
    void aPlanScopedCouponNeedsTheChargeToNameThatPlan() {
        UUID plan = UUID.randomUUID();
        DiscountCandidate candidate = candidate(
                coupon(new Benefit.PercentOff(2_000), new Scope(Set.of(), Set.of(plan), Set.of()), OPEN, true), null);

        assertThat(DiscountPlanner.plan(List.of(candidate), ChargeFacts.of(ChargeKind.USAGE, Money.of(100_000, USD)), NOW).isDiscounted())
                .as("a charge with no plan never matches a plan filter").isFalse();
        assertThat(DiscountPlanner.plan(List.of(candidate), ChargeFacts.of(ChargeKind.USAGE, UUID.randomUUID(), Money.of(100_000, USD)), NOW)
                .isDiscounted()).as("another plan does not match").isFalse();
        assertThat(DiscountPlanner.plan(List.of(candidate), ChargeFacts.of(ChargeKind.USAGE, plan, Money.of(100_000, USD)), NOW).isDiscounted())
                .isTrue();
    }

    @Test
    void aMetricScopedCouponNeedsTheChargeToSupplyMetrics() {
        DiscountCandidate candidate = candidate(
                coupon(new Benefit.PercentOff(2_000), new Scope(Set.of(), Set.of(), Set.of("api_calls")), OPEN, true), null);

        assertThat(DiscountPlanner.plan(List.of(candidate), ChargeFacts.of(ChargeKind.USAGE, Money.of(100_000, USD)), NOW).isDiscounted())
                .isFalse();
        assertThat(DiscountPlanner.plan(List.of(candidate),
                new ChargeFacts(ChargeKind.USAGE, null, Money.of(100_000, USD), Map.of("api_calls", Money.of(100_000, USD))), NOW).isDiscounted())
                .isTrue();
    }

    @Test
    void aCreditCouponNeverDiscountsACharge() {
        Coupon credit = coupon(new Benefit.WalletCredit(Money.of(10_000, USD)), Scope.everything(), OPEN, true);

        assertThat(plan(List.of(candidate(credit, null))).isDiscounted()).isFalse();
    }

    @Test
    void aCouponInAnotherCurrencyDoesNotApply() {
        DiscountQuote quote = plan(List.of(candidate(coupon(new Benefit.PercentOff(2_000), Scope.everything(), OPEN, true), null)),
                Money.of(100_000, Currency.of("EUR")));

        assertThat(quote.isDiscounted()).isFalse();
    }

    @Test
    void noCandidatesLeavesTheWholeCharge() {
        DiscountQuote quote = DiscountPlanner.plan(List.of(), ChargeFacts.of(ChargeKind.STANDALONE, Money.of(42, USD)), NOW);

        assertThat(quote.net()).isEqualTo(Money.of(42, USD));
        assertThat(quote.lines()).isEmpty();
    }

    private static DiscountQuote plan(List<DiscountCandidate> candidates) {
        return plan(candidates, Money.of(100_000, USD));
    }

    private static DiscountQuote plan(List<DiscountCandidate> candidates, Money gross) {
        return DiscountPlanner.plan(candidates, ChargeFacts.of(ChargeKind.STANDALONE, gross), NOW);
    }

    private static Coupon coupon(Benefit benefit, Scope scope, Limits limits, boolean stackable) {
        return Coupon.create(new CouponId(UUID.randomUUID()), TENANT, "Coupon", benefit, Duration.forever(), scope, limits, USD, stackable,
                "admin", REDEEMED);
    }

    private static DiscountCandidate candidate(Coupon coupon, Money remainingBudget) {
        return new DiscountCandidate(coupon, active(coupon, remainingBudget), remainingBudget);
    }

    private static Redemption active(Coupon coupon, Money maxTotalDiscount) {
        return Redemption.activate(new RedemptionId(UUID.randomUUID()), TENANT, coupon.id(), new PromotionCodeId(UUID.randomUUID()), WALLET,
                CUSTOMER, null, maxTotalDiscount, null, "customer", new ReplayIdentity(UUID.randomUUID().toString(), "h"), USD, REDEEMED);
    }

    private static Redemption activeAt(Coupon coupon, Instant redeemedAt) {
        return Redemption.activate(new RedemptionId(UUID.randomUUID()), TENANT, coupon.id(), new PromotionCodeId(UUID.randomUUID()), WALLET,
                CUSTOMER, null, null, null, "customer", new ReplayIdentity(UUID.randomUUID().toString(), "h"), USD, redeemedAt);
    }
}
