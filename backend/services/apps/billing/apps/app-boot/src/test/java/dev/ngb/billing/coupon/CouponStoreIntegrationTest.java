package dev.ngb.billing.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.billing.Application;
import dev.ngb.billing.coupon.application.coupon.port.CouponRepository;
import dev.ngb.billing.coupon.application.coupon.port.PromotionCodeRepository;
import dev.ngb.billing.coupon.application.redemption.port.RedemptionRepository;
import dev.ngb.billing.coupon.domain.coupon.Benefit;
import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.Duration;
import dev.ngb.billing.coupon.domain.coupon.Limits;
import dev.ngb.billing.coupon.domain.coupon.PromotionCode;
import dev.ngb.billing.coupon.domain.coupon.PromotionCodeId;
import dev.ngb.billing.coupon.domain.coupon.Scope;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.billing.coupon.domain.redemption.RedemptionId;
import dev.ngb.billing.coupon.domain.redemption.RedemptionStatus;
import dev.ngb.billing.money.MoneyTestWorld;
import dev.ngb.billing.money.MoneyTestWorld.World;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.support.OwnerDatabase;
import dev.ngb.billing.support.PostgresContainerConfig;
import dev.ngb.billing.support.ScopeTestSupport;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

/** The coupon store against a real database: round trips, row-level security and the guarded counters. */
@SpringBootTest(classes = Application.class)
@Import(PostgresContainerConfig.class)
class CouponStoreIntegrationTest {

    private static final Currency USD = Currency.of("USD");
    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

    @Autowired MoneyTestWorld worlds;
    @Autowired ScopeTestSupport scopes;
    @Autowired dev.ngb.core.runtime.UnitOfWork unitOfWork;
    @Autowired CouponRepository coupons;
    @Autowired PromotionCodeRepository codes;
    @Autowired RedemptionRepository redemptions;
    @Autowired OwnerDatabase owner;

    /** Runs a repository call in the tenant's scope and in one transaction, which is where row-level security reads the tenant. */
    private <T> T inTenant(TenantId tenantId, java.util.function.Supplier<T> work) {
        return scopes.inTenant(tenantId, () -> unitOfWork.transactional(work));
    }

    private void runInTenant(TenantId tenantId, Runnable work) {
        scopes.runInTenant(tenantId, () -> unitOfWork.transactional(work));
    }

    @Test
    void aCouponRoundTripsWithItsBenefitDurationScopeAndLimits() {
        World world = worlds.prepaid(0);
        Coupon coupon = coupon(world.tenant().id(), new Benefit.PercentOff(2_000),
                new Limits(NOW.plusSeconds(86_400), 100L, 2, Money.of(1_000, USD), Money.of(5_000, USD), Money.of(50_000, USD)));

        runInTenant(world.tenant().id(), () -> coupons.add(coupon));
        Coupon loaded = inTenant(world.tenant().id(), () -> coupons.findById(coupon.id())).orElseThrow();

        assertThat(loaded.name()).isEqualTo(coupon.name());
        assertThat(loaded.benefit()).isEqualTo(new Benefit.PercentOff(2_000));
        assertThat(loaded.stackable()).isTrue();
        assertThat(loaded.limits().maxRedemptions()).isEqualTo(100L);
        assertThat(loaded.limits().maxPerCustomer()).isEqualTo(2);
        assertThat(loaded.limits().budget()).isEqualTo(Money.of(50_000, USD));
        assertThat(loaded.scope()).isEqualTo(Scope.everything());
        assertThat(loaded.status()).isEqualTo(dev.ngb.billing.coupon.domain.coupon.CouponStatus.ACTIVE);
    }

    @Test
    void deactivationIsWrittenAndKeepsTheFirstTime() {
        World world = worlds.prepaid(0);
        Coupon coupon = coupon(world.tenant().id(), new Benefit.AmountOff(Money.of(1_000, USD)), Limits.boundedBy(NOW.plusSeconds(86_400)));
        runInTenant(world.tenant().id(), () -> coupons.add(coupon));

        coupon.deactivate(NOW);
        runInTenant(world.tenant().id(), () -> coupons.update(coupon));

        Coupon loaded = inTenant(world.tenant().id(), () -> coupons.findById(coupon.id())).orElseThrow();
        assertThat(loaded.status()).isEqualTo(dev.ngb.billing.coupon.domain.coupon.CouponStatus.INACTIVE);
        assertThat(loaded.deactivatedAt()).isEqualTo(NOW);
    }

    @Test
    void theRedemptionCountStopsAtItsMaximumUnderConcurrency() throws Exception {
        World world = worlds.prepaid(0);
        Coupon coupon = coupon(world.tenant().id(), new Benefit.PercentOff(1_000),
                new Limits(NOW.plusSeconds(86_400), 5L, null, null, null, null));
        runInTenant(world.tenant().id(), () -> coupons.add(coupon));

        int counted = countConcurrently(20, () -> inTenant(world.tenant().id(), () -> coupons.countRedemption(coupon.id())));

        assertThat(counted).as("exactly the coupon's maximum was counted").isEqualTo(5);
        assertThat(owner.count("select redemptions_count from coupons where id = ?", coupon.id().value())).isEqualTo(5);
    }

    @Test
    void theBudgetIsSpentUpToItsLimitAndNeverBeyond() throws Exception {
        World world = worlds.prepaid(0);
        Coupon coupon = coupon(world.tenant().id(), new Benefit.PercentOff(1_000),
                new Limits(NOW.plusSeconds(86_400), null, null, null, null, Money.of(1_000, USD)));
        runInTenant(world.tenant().id(), () -> coupons.add(coupon));

        List<Long> spent = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            spent.add(inTenant(world.tenant().id(), () -> coupons.spendBudget(coupon.id(), 100)));
        }

        assertThat(spent.stream().mapToLong(Long::longValue).sum()).isEqualTo(1_000);
        assertThat(owner.count("select budget_used from coupons where id = ?", coupon.id().value())).isEqualTo(1_000);
    }

    @Test
    void aCodeIsStoredUpperCasedAndIsUniquePerTenant() {
        World world = worlds.prepaid(0);
        Coupon coupon = coupon(world.tenant().id(), new Benefit.PercentOff(1_000), Limits.boundedBy(NOW.plusSeconds(86_400)));
        runInTenant(world.tenant().id(), () -> coupons.add(coupon));
        PromotionCode code = PromotionCode.create(new PromotionCodeId(UUID.randomUUID()), world.tenant().id(), coupon.id(), " save20 ",
                10L, null, "admin", NOW);
        runInTenant(world.tenant().id(), () -> codes.add(code));

        assertThat(inTenant(world.tenant().id(), () -> codes.findByCode("SAVE20"))).isPresent();
        assertThat(inTenant(world.tenant().id(), () -> codes.lockByCode("SAVE20"))).isPresent();
        assertThatThrownBy(() -> runInTenant(world.tenant().id(), () -> codes.add(PromotionCode.create(
                new PromotionCodeId(UUID.randomUUID()), world.tenant().id(), coupon.id(), "SAVE20", null, null, "admin", NOW))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theCodeRedemptionCountStopsAtItsMaximum() throws Exception {
        World world = worlds.prepaid(0);
        Coupon coupon = coupon(world.tenant().id(), new Benefit.PercentOff(1_000), Limits.boundedBy(NOW.plusSeconds(86_400)));
        runInTenant(world.tenant().id(), () -> coupons.add(coupon));
        PromotionCode code = PromotionCode.create(new PromotionCodeId(UUID.randomUUID()), world.tenant().id(), coupon.id(), "ONCE",
                3L, null, "admin", NOW);
        runInTenant(world.tenant().id(), () -> codes.add(code));

        int counted = countConcurrently(12, () -> inTenant(world.tenant().id(), () -> codes.countRedemption(code.id())));

        assertThat(counted).isEqualTo(3);
    }

    @Test
    void aSecondActiveRedemptionOfOneCouponOnOneWalletIsRefusedByTheDatabase() {
        World world = worlds.prepaid(0);
        Coupon coupon = coupon(world.tenant().id(), new Benefit.PercentOff(1_000), Limits.boundedBy(NOW.plusSeconds(86_400)));
        runInTenant(world.tenant().id(), () -> coupons.add(coupon));

        runInTenant(world.tenant().id(), () -> redemptions.add(active(world, coupon, "first")));

        assertThatThrownBy(() -> runInTenant(world.tenant().id(), () -> redemptions.add(active(world, coupon, "second"))))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("coupon_redemptions_active_idx");
    }

    @Test
    void aRedemptionRoundTripsAndItsCountersOnlyGrow() {
        World world = worlds.prepaid(0);
        Coupon coupon = coupon(world.tenant().id(), new Benefit.PercentOff(1_000), Limits.boundedBy(NOW.plusSeconds(86_400)));
        runInTenant(world.tenant().id(), () -> coupons.add(coupon));
        Redemption redemption = active(world, coupon, "round-trip");
        runInTenant(world.tenant().id(), () -> redemptions.add(redemption));

        assertThat(inTenant(world.tenant().id(), () -> redemptions.recordApplication(redemption.id(), 250, NOW))).isTrue();
        assertThat(inTenant(world.tenant().id(), () -> redemptions.recordApplication(redemption.id(), 250, NOW))).isTrue();

        Redemption loaded = inTenant(world.tenant().id(), () -> redemptions.findById(redemption.id())).orElseThrow();
        assertThat(loaded.applicationsUsed()).isEqualTo(2);
        assertThat(loaded.discountTotal()).isEqualTo(Money.of(500, USD));
        assertThat(loaded.status()).isEqualTo(RedemptionStatus.ACTIVE);
        assertThat(inTenant(world.tenant().id(), () -> redemptions.findByIdempotencyKey(redemption.idempotencyKey()))).isPresent();
    }

    @Test
    void anExpiredRedemptionRecordsNoApplication() {
        World world = worlds.prepaid(0);
        Coupon coupon = coupon(world.tenant().id(), new Benefit.PercentOff(1_000), Limits.boundedBy(NOW.plusSeconds(86_400)));
        runInTenant(world.tenant().id(), () -> coupons.add(coupon));
        Redemption redemption = Redemption.activate(new RedemptionId(UUID.randomUUID()), world.tenant().id(), coupon.id(), null,
                world.wallet().id(), world.customerId(), null, null, NOW.minusSeconds(1), "customer", new ReplayIdentity("expired", "h"), USD,
                NOW.minusSeconds(60));
        runInTenant(world.tenant().id(), () -> redemptions.add(redemption));

        assertThat(inTenant(world.tenant().id(), () -> redemptions.recordApplication(redemption.id(), 100, NOW))).isFalse();
        assertThat(owner.count("select applications_used from coupon_redemptions where id = ?", redemption.id().value())).isZero();
    }

    @Test
    void aRedemptionCannotExceedItsOwnTotalDiscount() {
        World world = worlds.prepaid(0);
        Coupon coupon = coupon(world.tenant().id(), new Benefit.PercentOff(1_000), Limits.boundedBy(NOW.plusSeconds(86_400)));
        runInTenant(world.tenant().id(), () -> coupons.add(coupon));
        Redemption redemption = Redemption.activate(new RedemptionId(UUID.randomUUID()), world.tenant().id(), coupon.id(), null,
                world.wallet().id(), world.customerId(), null, Money.of(300, USD), null, "customer", new ReplayIdentity("capped", "h"), USD, NOW);
        runInTenant(world.tenant().id(), () -> redemptions.add(redemption));

        assertThat(inTenant(world.tenant().id(), () -> redemptions.recordApplication(redemption.id(), 301, NOW))).isFalse();
        assertThat(owner.count("select discount_total from coupon_redemptions where id = ?", redemption.id().value())).isZero();
    }

    @Test
    void locksReadTheActiveRedemptionsInIdOrder() {
        World world = worlds.prepaid(0);
        Coupon firstCoupon = coupon(world.tenant().id(), new Benefit.PercentOff(1_000), Limits.boundedBy(NOW.plusSeconds(86_400)));
        Coupon secondCoupon = coupon(world.tenant().id(), new Benefit.PercentOff(500), Limits.boundedBy(NOW.plusSeconds(86_400)));
        runInTenant(world.tenant().id(), () -> coupons.add(firstCoupon));
        runInTenant(world.tenant().id(), () -> coupons.add(secondCoupon));
        Redemption first = active(world, firstCoupon, "lock-1");
        Redemption second = active(world, secondCoupon, "lock-2");
        runInTenant(world.tenant().id(), () -> redemptions.add(first));
        runInTenant(world.tenant().id(), () -> redemptions.add(second));

        List<Redemption> locked = inTenant(world.tenant().id(), () -> redemptions.lockActiveByWallet(world.wallet().id()));

        assertThat(locked).extracting(Redemption::id).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(locked).extracting(redemption -> redemption.id().value().toString())
                .as("locked in ascending id order, so two charges cannot deadlock")
                .isSorted();
        assertThat(inTenant(world.tenant().id(), () -> redemptions.countForCustomer(world.customerId(), firstCoupon.id()))).isEqualTo(1);
        assertThat(inTenant(world.tenant().id(), () -> redemptions.lockActiveByCoupon(firstCoupon.id())))
                .extracting(Redemption::id).containsExactly(first.id());
    }

    @Test
    void anotherTenantSeesNoCouponCodeOrRedemption() {
        World mine = worlds.prepaid(0);
        World theirs = worlds.prepaid(0);
        Coupon coupon = coupon(mine.tenant().id(), new Benefit.PercentOff(1_000), Limits.boundedBy(NOW.plusSeconds(86_400)));
        runInTenant(mine.tenant().id(), () -> coupons.add(coupon));
        Redemption redemption = active(mine, coupon, "hidden");
        runInTenant(mine.tenant().id(), () -> redemptions.add(redemption));

        assertThat(inTenant(theirs.tenant().id(), () -> coupons.findById(coupon.id()))).isEmpty();
        assertThat(inTenant(theirs.tenant().id(), () -> redemptions.findById(redemption.id()))).isEmpty();
        assertThat(inTenant(theirs.tenant().id(), () -> redemptions.lockActiveByWallet(mine.wallet().id()))).isEmpty();
        assertThat(inTenant(theirs.tenant().id(), () -> redemptions.findByIdempotencyKey(redemption.idempotencyKey()))).isEmpty();
    }

    @Test
    void aCouponWithoutATotalBoundIsRefusedByTheDatabaseToo() {
        World world = worlds.prepaid(0);

        assertThatThrownBy(() -> owner.jdbc().update("""
                        insert into coupons (id, tenant_id, name, currency, benefit_type, percent_basis_points, stackable, status, created_by, created_at)
                        values (?, ?, 'unbounded', 'USD', 'PERCENT_OFF', 1000, true, 'ACTIVE', 'attacker', now())
                        """, UUID.randomUUID(), world.tenant().id().value()))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("coupons_check");
    }

    private Redemption active(World world, Coupon coupon, String key) {
        return Redemption.activate(new RedemptionId(UUID.randomUUID()), world.tenant().id(), coupon.id(), null, world.wallet().id(),
                world.customerId(), null, null, null, "customer", new ReplayIdentity(key, "hash"), USD, NOW);
    }

    private Coupon coupon(TenantId tenantId, Benefit benefit, Limits limits) {
        return Coupon.create(new CouponId(UUID.randomUUID()), tenantId, "Coupon " + UUID.randomUUID(), benefit, Duration.forever(),
                Scope.everything(), limits, USD, true, "admin", NOW);
    }

    private static <T> int countConcurrently(int attempts, java.util.function.Supplier<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<CompletableFuture<T>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                futures.add(CompletableFuture.supplyAsync(work::get, pool));
            }
            int counted = 0;
            for (CompletableFuture<T> future : futures) {
                if (Boolean.TRUE.equals(future.get(120, TimeUnit.SECONDS))) {
                    counted++;
                }
            }
            return counted;
        } finally {
            pool.shutdownNow();
        }
    }
}
