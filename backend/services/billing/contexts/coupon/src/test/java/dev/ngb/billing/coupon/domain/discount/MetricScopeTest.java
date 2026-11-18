package dev.ngb.billing.coupon.domain.discount;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.coupon.domain.coupon.Benefit;
import dev.ngb.billing.coupon.domain.coupon.ChargeKind;
import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.Duration;
import dev.ngb.billing.coupon.domain.coupon.Limits;
import dev.ngb.billing.coupon.domain.coupon.Scope;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.billing.coupon.domain.redemption.RedemptionId;
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

/**
 * A coupon limited to named metrics discounts only those metrics' share of a usage charge. The base is what is still
 * undiscounted of the coupon's own metrics, capped by what is still owed, and applying one reduces those subtotals
 * pro rata so two metric coupons on the same metric cannot discount it twice.
 */
class MetricScopeTest {

    private static final Currency USD = Currency.of("USD");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private static final Limits OPEN = new Limits(NOW.plusSeconds(86_400), null, null, null, null, null);

    @Test
    void aCouponScopedToOneMetricDiscountsOnlyItsShare() {
        Coupon coupon = coupon(new Benefit.PercentOff(5_000), Set.of("api_calls"));

        DiscountQuote quote = plan(coupon, facts(Map.of("api_calls", 40_000L, "storage", 60_000L)));

        assertThat(quote.lines()).singleElement().satisfies(line -> assertThat(line.amount().minorUnits()).isEqualTo(20_000L));
        assertThat(quote.net().minorUnits()).isEqualTo(80_000L);
    }

    @Test
    void aFixedAmountOnOneMetricIsCappedAtThatMetricsShare() {
        Coupon coupon = coupon(new Benefit.AmountOff(Money.of(50_000, USD)), Set.of("api_calls"));

        DiscountQuote quote = plan(coupon, facts(Map.of("api_calls", 10_000L, "storage", 90_000L)));

        assertThat(quote.lines()).singleElement().satisfies(line -> assertThat(line.amount().minorUnits()).isEqualTo(10_000L));
        assertThat(quote.net().minorUnits()).isEqualTo(90_000L);
    }

    @Test
    void twoMetricCouponsOnTheSameMetricNeverExceedItsSubtotal() {
        Coupon first = coupon(new Benefit.PercentOff(8_000), Set.of("api_calls"));
        Coupon second = coupon(new Benefit.PercentOff(8_000), Set.of("api_calls"));

        DiscountQuote quote = plan(List.of(candidate(first), candidate(second)), facts(Map.of("api_calls", 40_000L, "storage", 60_000L)));

        long apiDiscount = quote.lines().stream().mapToLong(line -> line.amount().minorUnits()).sum();
        assertThat(apiDiscount).as("the metric cannot be discounted by more than it cost").isLessThanOrEqualTo(40_000L);
        assertThat(quote.net().minorUnits()).isEqualTo(100_000L - apiDiscount);
    }

    @Test
    void anUnscopedCouponStillActsOnTheWholeCharge() {
        Coupon coupon = coupon(new Benefit.PercentOff(1_000), Set.of());

        DiscountQuote quote = plan(coupon, facts(Map.of("api_calls", 40_000L, "storage", 60_000L)));

        assertThat(quote.lines()).singleElement().satisfies(line -> assertThat(line.amount().minorUnits()).isEqualTo(10_000L));
    }

    @Test
    void metricSubtotalsAreScaledDownWhenSomeoneElseCappedTheGross() {
        // The lines sum to more than the gross because the maximum charge replaced the calculated amount.
        Coupon coupon = coupon(new Benefit.PercentOff(5_000), Set.of("api_calls"));
        ChargeFacts facts = new ChargeFacts(ChargeKind.USAGE, null, Money.of(50_000, USD),
                Map.of("api_calls", Money.of(40_000, USD), "storage", Money.of(60_000, USD)));

        DiscountQuote quote = plan(coupon, facts);

        assertThat(quote.lines()).singleElement()
                .satisfies(line -> assertThat(line.amount().minorUnits())
                        .as("40,000 of a 100,000 breakdown scales into a 50,000 gross as 20,000; half of that is 10,000")
                        .isEqualTo(10_000L));
    }

    @Test
    void aMetricScopedCouponDoesNotApplyToAChargeWithoutMetrics() {
        Coupon coupon = coupon(new Benefit.PercentOff(9_000), Set.of("api_calls"));

        DiscountQuote quote = DiscountPlanner.plan(List.of(candidate(coupon)), ChargeFacts.of(ChargeKind.STANDALONE, Money.of(100_000, USD)), NOW);

        assertThat(quote.isDiscounted()).isFalse();
    }

    private static ChargeFacts facts(Map<String, Long> metricAmounts) {
        Money gross = Money.zero(USD);
        Map<String, Money> amounts = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : metricAmounts.entrySet()) {
            amounts.put(entry.getKey(), Money.of(entry.getValue(), USD));
            gross = gross.plus(Money.of(entry.getValue(), USD));
        }
        return new ChargeFacts(ChargeKind.USAGE, null, gross, amounts);
    }

    private static DiscountQuote plan(Coupon coupon, ChargeFacts facts) {
        return plan(List.of(candidate(coupon)), facts);
    }

    private static DiscountQuote plan(List<DiscountCandidate> candidates, ChargeFacts facts) {
        return DiscountPlanner.plan(candidates, facts, NOW);
    }

    private static Coupon coupon(Benefit benefit, Set<String> metrics) {
        return Coupon.create(new CouponId(UUID.randomUUID()), TENANT, "Metric " + String.join(",", metrics), benefit, Duration.forever(),
                new Scope(Set.of(), Set.of(), metrics), OPEN, USD, true, "admin", NOW.minusSeconds(3_600));
    }

    private static DiscountCandidate candidate(Coupon coupon) {
        Redemption redemption = Redemption.activate(new RedemptionId(UUID.randomUUID()), TENANT, coupon.id(), null,
                new WalletId(UUID.randomUUID()), new CustomerId(UUID.randomUUID()), null, null, null, "customer",
                new ReplayIdentity(UUID.randomUUID().toString(), "h"), USD, NOW.minusSeconds(60));
        return new DiscountCandidate(coupon, redemption, null);
    }
}
