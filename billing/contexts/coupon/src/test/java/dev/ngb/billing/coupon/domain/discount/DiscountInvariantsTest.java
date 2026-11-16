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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The safety net for the stacking and capping rules. The golden cases pin the documented examples; these properties
 * say that no combination of coupons, however they are put together, can produce a nonsense result. The seed is fixed
 * so a failure is reproducible.
 */
class DiscountInvariantsTest {

    private static final Currency USD = Currency.of("USD");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private static final CustomerId CUSTOMER = new CustomerId(UUID.randomUUID());
    private static final WalletId WALLET = new WalletId(UUID.randomUUID());
    private static final int CASES = 10_000;

    @Test
    void theNetAlwaysSitsBetweenZeroAndTheGross() {
        Random random = new Random(11_016);

        for (int i = 0; i < CASES; i++) {
            Fixture fixture = fixture(random);

            DiscountQuote quote = fixture.quote();

            assertThat(quote.net().isNegative()).as("case %d: net %s", i, quote.net()).isFalse();
            assertThat(quote.net().isGreaterThan(quote.gross())).as("case %d: net above gross", i).isFalse();
        }
    }

    @Test
    void theLinesAlwaysSumToGrossMinusNet() {
        Random random = new Random(22_016);

        for (int i = 0; i < CASES; i++) {
            DiscountQuote quote = fixture(random).quote();

            Money sum = Money.zero(USD);
            for (AppliedDiscount line : quote.lines()) {
                sum = sum.plus(line.amount());
            }
            assertThat(sum).as("case %d: lines must sum to gross minus net", i).isEqualTo(quote.gross().minus(quote.net()));
        }
    }

    @Test
    void noLineExceedsAnyCapThatAppliesToIt() {
        Random random = new Random(33_016);

        for (int i = 0; i < CASES; i++) {
            Fixture fixture = fixture(random);
            DiscountQuote quote = fixture.quote();

            for (AppliedDiscount line : quote.lines()) {
                Fixture.Capped candidate = fixture.candidateFor(line);
                if (candidate.perApplication() != null) {
                    assertThat(line.amount().isGreaterThan(candidate.perApplication()))
                            .as("case %d: above the per-application cap", i).isFalse();
                }
                if (candidate.remainingTotal() != null) {
                    assertThat(line.amount().isGreaterThan(candidate.remainingTotal()))
                            .as("case %d: above the redemption's remaining total", i).isFalse();
                }
                if (candidate.remainingBudget() != null) {
                    assertThat(line.amount().isGreaterThan(candidate.remainingBudget()))
                            .as("case %d: above the coupon's remaining budget", i).isFalse();
                }
            }
        }
    }

    @Test
    void shufflingTheCandidatesChangesNothing() {
        Random random = new Random(44_016);

        for (int i = 0; i < CASES; i++) {
            Fixture fixture = fixture(random);
            List<DiscountCandidate> shuffled = new ArrayList<>(fixture.candidates);
            Collections.shuffle(shuffled, random);

            DiscountQuote original = fixture.quote();
            DiscountQuote permuted = DiscountPlanner.plan(shuffled, ChargeFacts.of(ChargeKind.STANDALONE, fixture.gross), NOW);

            assertThat(permuted.net()).as("case %d: order must not matter", i).isEqualTo(original.net());
        }
    }

    @Test
    void aNonStackableCouponNeverAppearsBesideAnother() {
        Random random = new Random(55_016);

        for (int i = 0; i < CASES; i++) {
            Fixture fixture = fixture(random);
            DiscountQuote quote = fixture.quote();

            boolean exclusive = quote.lines().stream().anyMatch(line -> !fixture.couponFor(line.couponId()).stackable());
            if (exclusive) {
                assertThat(quote.lines()).as("case %d: an exclusive coupon runs alone", i).hasSize(1);
            }
        }
    }

    /** One random arrangement of benefits, caps, budgets and stacking flags over one gross. */
    private static Fixture fixture(Random random) {
        Money gross = Money.of(1 + random.nextInt(200_000), USD);
        int count = random.nextInt(4);
        List<DiscountCandidate> candidates = new ArrayList<>();
        List<Fixture.Capped> capped = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Benefit benefit = switch (random.nextInt(3)) {
                case 0 -> new Benefit.PercentOff(1 + random.nextInt(10_000));
                case 1 -> new Benefit.AmountOff(Money.of(1 + random.nextInt(150_000), USD));
                default -> new Benefit.WalletCredit(Money.of(1 + random.nextInt(120_000), USD));
            };
            Money perApplication = random.nextBoolean() ? null : Money.of(1 + random.nextInt(80_000), USD);
            Money budget = random.nextBoolean() ? null : Money.of(1 + random.nextInt(300_000), USD);
            Money remainingTotal = random.nextBoolean() ? null : Money.of(1 + random.nextInt(200_000), USD);
            Limits limits = new Limits(NOW.plusSeconds(86_400), null, null, null, perApplication, budget);
            Coupon coupon = Coupon.create(new CouponId(UUID.randomUUID()), TENANT, "Random", benefit, Duration.forever(), Scope.everything(),
                    limits, USD, random.nextBoolean(), "admin", NOW.minusSeconds(3_600));
            Redemption redemption = Redemption.activate(new RedemptionId(UUID.randomUUID()), TENANT, coupon.id(), null, WALLET, CUSTOMER,
                    null, remainingTotal, null, "customer", new ReplayIdentity(UUID.randomUUID().toString(), "h"), USD, NOW.minusSeconds(600));
            candidates.add(new DiscountCandidate(coupon, redemption, budget));
            capped.add(new Fixture.Capped(coupon, perApplication, remainingTotal, budget));
        }
        return new Fixture(gross, candidates, capped);
    }

    private record Fixture(Money gross, List<DiscountCandidate> candidates, List<Fixture.Capped> capped) {

        DiscountQuote quote() {
            return DiscountPlanner.plan(candidates, ChargeFacts.of(ChargeKind.STANDALONE, gross), NOW);
        }

        Fixture.Capped candidateFor(AppliedDiscount line) {
            return capped.stream()
                    .filter(candidate -> candidate.coupon().id().equals(line.couponId()))
                    .findFirst()
                    .orElseThrow();
        }

        Coupon couponFor(CouponId id) {
            return capped.stream().map(Fixture.Capped::coupon).filter(coupon -> coupon.id().equals(id)).findFirst().orElseThrow();
        }

        record Capped(Coupon coupon, Money perApplication, Money remainingTotal, Money remainingBudget) {}
    }
}
