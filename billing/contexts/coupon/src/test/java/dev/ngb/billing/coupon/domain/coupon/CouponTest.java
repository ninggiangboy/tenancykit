package dev.ngb.billing.coupon.domain.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CouponTest {

    private static final Currency USD = Currency.of("USD");
    private static final dev.ngb.core.id.TenantId TENANT = new dev.ngb.core.id.TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

    @Test
    void aCouponWithoutAnyTotalBoundIsRefused() {
        assertThatThrownBy(() -> new Limits(null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total bound");
    }

    @Test
    void aPercentBenefitIsBoundedToOneHundredthOfAPercentAndOneHundredPercent() {
        assertThatThrownBy(() -> new Benefit.PercentOff(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Benefit.PercentOff(10_001)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Benefit.PercentOff(1).basisPoints()).isEqualTo(1);
        assertThat(new Benefit.PercentOff(10_000).basisPoints()).isEqualTo(10_000);
    }

    @Test
    void anAmountBenefitAndACreditMustBePositive() {
        assertThatThrownBy(() -> new Benefit.AmountOff(Money.zero(USD))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Benefit.AmountOff(Money.of(-1, USD))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Benefit.WalletCredit(Money.zero(USD))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBenefitInAnotherCurrencyThanTheCouponIsRefused() {
        assertThatThrownBy(() -> coupon(new Benefit.AmountOff(Money.of(1_000, Currency.of("EUR")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void aDurationWithNoLimitRunsForever() {
        assertThat(Duration.forever().isUnlimited()).isTrue();
        assertThat(new Duration(TimeWindow.days(30), null, null).isUnlimited()).isFalse();
    }

    @Test
    void aTimeWindowCountsCalendarMonthsNotDays() {
        Instant january31 = Instant.parse("2026-01-31T00:00:00Z");

        assertThat(TimeWindow.months(1).endsAt(january31)).isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));
        assertThat(TimeWindow.days(1).endsAt(january31)).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    void aCouponIsCreatedActiveAndDeactivationIsFinalAndIdempotent() {
        Coupon coupon = coupon(new Benefit.PercentOff(2_000));

        assertThat(coupon.isActive()).isTrue();
        assertThat(coupon.deactivatedAt()).isNull();

        coupon.deactivate(NOW);
        Instant first = coupon.deactivatedAt();
        coupon.deactivate(NOW.plusSeconds(60));

        assertThat(coupon.isActive()).isFalse();
        assertThat(coupon.deactivatedAt()).isEqualTo(first);
        assertThat(coupon.isOpenForRedemption(NOW)).isFalse();
    }

    @Test
    void redemptionClosesAtRedeemBy() {
        Coupon coupon = coupon(new Benefit.PercentOff(2_000));

        assertThat(coupon.isOpenForRedemption(NOW.plusSeconds(3_599))).isTrue();
        assertThat(coupon.isOpenForRedemption(NOW.plusSeconds(3_600))).isFalse();
    }

    @Test
    void anEmptyScopeMatchesAnyDimension() {
        Scope scope = Scope.everything();

        assertThat(scope.matchesAnyKind()).isTrue();
        assertThat(scope.isPlanScoped()).isFalse();
        assertThat(scope.isMetricScoped()).isFalse();
    }

    @Test
    void aCreditCouponIsRecognisedByItsBenefit() {
        assertThat(coupon(new Benefit.WalletCredit(Money.of(10_000, USD))).isCredit()).isTrue();
        assertThat(coupon(new Benefit.PercentOff(500)).isCredit()).isFalse();
    }

    private static Coupon coupon(Benefit benefit) {
        return Coupon.create(new CouponId(UUID.randomUUID()), TENANT, "Launch", benefit, Duration.forever(), Scope.everything(),
                new Limits(NOW.plusSeconds(3_600), null, null, null, null, null), USD, true, "admin", NOW);
    }
}
