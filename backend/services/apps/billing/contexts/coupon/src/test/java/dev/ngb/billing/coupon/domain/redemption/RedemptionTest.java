package dev.ngb.billing.coupon.domain.redemption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RedemptionTest {

    private static final Currency USD = Currency.of("USD");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

    @Test
    void aRedemptionWithNoLimitsConsumesForever() {
        Redemption redemption = active(null, null, null);

        for (int i = 0; i < 25; i++) {
            redemption.consume(Money.of(100, USD), NOW);
        }

        assertThat(redemption.status()).isEqualTo(RedemptionStatus.ACTIVE);
        assertThat(redemption.applicationsUsed()).isEqualTo(25);
    }

    @Test
    void theLastApplicationEndsTheRedemption() {
        Redemption redemption = active(2, null, null);

        redemption.consume(Money.of(100, USD), NOW);
        assertThat(redemption.status()).isEqualTo(RedemptionStatus.ACTIVE);

        redemption.consume(Money.of(100, USD), NOW);

        assertThat(redemption.status()).isEqualTo(RedemptionStatus.ENDED);
        assertThat(redemption.endReason()).isEqualTo(EndReason.EXHAUSTED);
        assertThat(redemption.endedAt()).isEqualTo(NOW);
        assertThat(redemption.isConsumable(NOW)).isFalse();
    }

    @Test
    void reachingTheTotalDiscountCapEndsTheRedemption() {
        Redemption redemption = active(null, Money.of(150, USD), null);

        redemption.consume(Money.of(150, USD), NOW);

        assertThat(redemption.status()).isEqualTo(RedemptionStatus.ENDED);
        assertThat(redemption.discountTotal()).isEqualTo(Money.of(150, USD));
        assertThat(redemption.remainingTotalDiscount()).isEqualTo(Money.zero(USD));
    }

    @Test
    void anApplicationCannotExceedWhatIsLeftOfTheTotalCap() {
        Redemption redemption = active(null, Money.of(150, USD), null);

        assertThatThrownBy(() -> redemption.consume(Money.of(151, USD), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remaining total discount");
    }

    @Test
    void anExpiredRedemptionIsNotConsumable() {
        Redemption redemption = Redemption.activate(new RedemptionId(UUID.randomUUID()), TENANT, new CouponId(UUID.randomUUID()), null,
                new WalletId(UUID.randomUUID()), new CustomerId(UUID.randomUUID()), null, null, NOW.minusSeconds(1), "customer",
                new ReplayIdentity("key", "hash"), USD, NOW.minusSeconds(60));

        assertThat(redemption.isConsumable(NOW)).isFalse();
        assertThatThrownBy(() -> redemption.consume(Money.of(1, USD), NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokingIsFinalAndIdempotent() {
        Redemption redemption = active(null, null, null);

        redemption.end(EndReason.REVOKED, NOW);
        redemption.end(EndReason.REVOKED, NOW.plusSeconds(60));

        assertThat(redemption.status()).isEqualTo(RedemptionStatus.REVOKED);
        assertThat(redemption.endReason()).isEqualTo(EndReason.REVOKED);
        assertThat(redemption.endedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> redemption.consume(Money.of(1, USD), NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aGrantedRedemptionIsFinishedFromTheStartWithOneApplication() {
        Redemption granted = Redemption.granted(new RedemptionId(UUID.randomUUID()), TENANT, new CouponId(UUID.randomUUID()), null,
                new WalletId(UUID.randomUUID()), new CustomerId(UUID.randomUUID()), "admin", new ReplayIdentity("key", "hash"), USD, NOW);

        assertThat(granted.status()).isEqualTo(RedemptionStatus.ENDED);
        assertThat(granted.endReason()).isEqualTo(EndReason.GRANTED);
        assertThat(granted.applicationsUsed()).isEqualTo(1);
        assertThat(granted.isConsumable(NOW)).isFalse();
    }

    private static Redemption active(Integer maxApplications, Money maxTotalDiscount, Instant endsAt) {
        return Redemption.activate(new RedemptionId(UUID.randomUUID()), TENANT, new CouponId(UUID.randomUUID()), null,
                new WalletId(UUID.randomUUID()), new CustomerId(UUID.randomUUID()), maxApplications, maxTotalDiscount, endsAt, "customer",
                new ReplayIdentity("key", "hash"), USD, NOW);
    }
}
