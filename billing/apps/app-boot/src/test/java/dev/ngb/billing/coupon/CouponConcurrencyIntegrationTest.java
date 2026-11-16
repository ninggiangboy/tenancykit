package dev.ngb.billing.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.Application;
import dev.ngb.billing.coupon.application.coupon.port.CouponRepository;
import dev.ngb.billing.coupon.application.redemption.port.RedemptionRepository;
import dev.ngb.billing.coupon.domain.coupon.Benefit;
import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.CouponStatus;
import dev.ngb.billing.coupon.domain.coupon.Duration;
import dev.ngb.billing.coupon.domain.coupon.Limits;
import dev.ngb.billing.coupon.domain.coupon.Scope;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.billing.coupon.domain.redemption.RedemptionId;
import dev.ngb.billing.money.MoneyTestWorld;
import dev.ngb.billing.money.MoneyTestWorld.World;
import dev.ngb.billing.money.api.charge.ChargeCommand;
import dev.ngb.billing.money.api.charge.ChargeWallet;
import dev.ngb.billing.money.api.wallet.WalletLookup;
import dev.ngb.billing.money.application.wallet.command.CreditWalletUseCase;
import dev.ngb.billing.money.application.wallet.command.OpenWalletUseCase;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.support.OwnerDatabase;
import dev.ngb.billing.support.PostgresContainerConfig;
import dev.ngb.billing.support.ScopeTestSupport;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The two races the coupon design has to survive on real PostgreSQL: a budget shared by many wallets is never
 * overspent, and one coupon's rows are invisible to another tenant.
 */
@SpringBootTest(classes = Application.class)
@Import(PostgresContainerConfig.class)
class CouponConcurrencyIntegrationTest {

    private static final Currency USD = Currency.of("USD");
    private static final Audit AUDIT = new Audit("user/test", "req-race");

    @Autowired MoneyTestWorld worlds;
    @Autowired CouponRepository coupons;
    @Autowired RedemptionRepository redemptions;
    @Autowired ChargeWallet chargeWallet;
    @Autowired CreditWalletUseCase creditWallet;
    @Autowired dev.ngb.billing.coupon.application.redemption.command.RedeemCouponUseCase redeem;
    @Autowired dev.ngb.billing.customer.application.customer.command.CreateCustomerUseCase createCustomer;
    @Autowired dev.ngb.billing.coupon.application.redemption.command.RevokeRedemptionUseCase revoke;
    @Autowired dev.ngb.billing.coupon.application.coupon.command.DeactivateCouponUseCase deactivate;
    @Autowired OpenWalletUseCase openWallet;
    @Autowired WalletLookup wallets;
    @Autowired ScopeTestSupport scopes;
    @Autowired UnitOfWork unitOfWork;
    @Autowired OwnerDatabase owner;

    private <T> T inTenant(World world, java.util.function.Supplier<T> work) {
        return scopes.inTenant(world.tenant().id(), () -> unitOfWork.transactional(work));
    }

    private Coupon budgetedCoupon(World world, long budget) {
        Coupon coupon = Coupon.create(new CouponId(UUID.randomUUID()), world.tenant().id(), "Budgeted", new Benefit.PercentOff(1_000),
                Duration.forever(), Scope.everything(),
                new Limits(Instant.parse("2027-01-01T00:00:00Z"), null, null, null, null, Money.of(budget, USD)), USD, true, "admin",
                Instant.now());
        inTenant(world, () -> {
            coupons.add(coupon);
            return coupon;
        });
        return coupon;
    }

    @Test
    void twentyParallelChargesAgainstOneBudgetNeverExceedIt() throws Exception {
        World world = worlds.postpaid(10_000_000);
        long budget = 5_000;
        Coupon coupon = budgetedCoupon(world, budget);

        // Twenty wallets, each holding the coupon, all charged at once against one shared budget.
        List<WalletId> wallets = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            CustomerId customer = scopes.inTenant(world.tenant().id(), () -> createCustomer.execute(
                    new dev.ngb.billing.customer.application.customer.command.CreateCustomerUseCase.Command(
                            null, null, null, "Pool " + UUID.randomUUID(), null, UUID.randomUUID().toString(), AUDIT))).customer().id();
            var opened = scopes.inTenant(world.tenant().id(), () -> openWallet.execute(new OpenWalletUseCase.Command(
                    customer, USD, dev.ngb.billing.money.api.wallet.WalletMode.POSTPAID, Money.of(1_000_000, USD), AUDIT))).wallet();
            wallets.add(opened.id());
            inTenant(world, () -> {
                redemptions.add(Redemption.activate(new RedemptionId(UUID.randomUUID()), world.tenant().id(), coupon.id(), null, opened.id(), customer,
                        null, null, null, "admin", new ReplayIdentity("hold-" + UUID.randomUUID(), "h"), USD, Instant.now()));
                return opened;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicLong totalFailure = new AtomicLong();
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (WalletId wallet : wallets) {
                futures.add(CompletableFuture.runAsync(() -> scopes.inTenant(world.tenant().id(),
                        () -> chargeWallet.charge(new ChargeCommand(wallet, null, Money.of(1_000, USD), null, "race", "race-" + UUID.randomUUID()))), pool));
            }
            for (CompletableFuture<Void> future : futures) {
                future.get(180, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        long spent = owner.count("select budget_used from coupons where id = ?", coupon.id().value());
        assertThat(spent).as("the budget is never overspent").isLessThanOrEqualTo(budget);
        long discounted = owner.count("select coalesce(sum(amount), 0) from charge_discounts where tenant_id = ?", world.tenant().id().value());
        assertThat(discounted).as("the lines match what the budget allowed").isEqualTo(spent);
        assertThat(totalFailure.get()).isZero();
    }

    @Test
    void aRevokeRacingChargesNeverRewindsTheRedemptionCounters() throws Exception {
        for (int round = 0; round < 5; round++) {
            World world = worlds.prepaid(50_000_000);
            Coupon coupon = Coupon.create(new CouponId(UUID.randomUUID()), world.tenant().id(), "Raced", new Benefit.PercentOff(1_000),
                    Duration.forever(), Scope.everything(), Limits.boundedBy(Instant.parse("2027-01-01T00:00:00Z")), USD, true, "admin", Instant.now());
            RedemptionId redemptionId = new RedemptionId(UUID.randomUUID());
            inTenant(world, () -> {
                coupons.add(coupon);
                redemptions.add(Redemption.activate(redemptionId, world.tenant().id(), coupon.id(), null, world.wallet().id(), world.customerId(),
                        null, null, null, "admin", new ReplayIdentity("race-" + UUID.randomUUID(), "h"), USD, Instant.now()));
                return coupon;
            });

            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    futures.add(CompletableFuture.runAsync(() -> scopes.inTenant(world.tenant().id(), () -> chargeWallet.charge(
                            new ChargeCommand(world.wallet().id(), null, Money.of(1_000, USD), null, "race", "rev-" + UUID.randomUUID()))), pool));
                }
                futures.add(CompletableFuture.runAsync(() -> scopes.inTenant(world.tenant().id(),
                        () -> revoke.execute(new dev.ngb.billing.coupon.application.redemption.command.RevokeRedemptionUseCase.Command(
                                redemptionId, AUDIT, null))), pool));
                for (CompletableFuture<Void> future : futures) {
                    future.get(180, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            long lines = owner.count("select count(*) from charge_discounts where redemption_id = ?", redemptionId.value());
            long taken = owner.count("select coalesce(sum(amount), 0) from charge_discounts where redemption_id = ?", redemptionId.value());
            assertThat(owner.count("select applications_used from coupon_redemptions where id = ?", redemptionId.value()))
                    .as("the counter equals the discounts that were given, however the revoke interleaved").isEqualTo(lines);
            assertThat(owner.count("select discount_total from coupon_redemptions where id = ?", redemptionId.value())).isEqualTo(taken);
        }
    }

    @Test
    void deactivatingWithRevocationNeverDeadlocksWithChargesOnABudgetedCoupon() throws Exception {
        for (int round = 0; round < 5; round++) {
            World world = worlds.postpaid(10_000_000);
            Coupon coupon = budgetedCoupon(world, 1_000_000);
            List<WalletId> wallets = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                CustomerId customer = scopes.inTenant(world.tenant().id(), () -> createCustomer.execute(
                        new dev.ngb.billing.customer.application.customer.command.CreateCustomerUseCase.Command(
                                null, null, null, "Dead " + UUID.randomUUID(), null, UUID.randomUUID().toString(), AUDIT))).customer().id();
                var opened = scopes.inTenant(world.tenant().id(), () -> openWallet.execute(new OpenWalletUseCase.Command(
                        customer, USD, dev.ngb.billing.money.api.wallet.WalletMode.POSTPAID, Money.of(1_000_000, USD), AUDIT))).wallet();
                wallets.add(opened.id());
                inTenant(world, () -> {
                    redemptions.add(Redemption.activate(new RedemptionId(UUID.randomUUID()), world.tenant().id(), coupon.id(), null, opened.id(),
                            customer, null, null, null, "admin", new ReplayIdentity("dead-" + UUID.randomUUID(), "h"), USD, Instant.now()));
                    return opened;
                });
            }

            ExecutorService pool = Executors.newFixedThreadPool(9);
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (WalletId wallet : wallets) {
                    for (int i = 0; i < 3; i++) {
                        futures.add(CompletableFuture.runAsync(() -> scopes.inTenant(world.tenant().id(), () -> chargeWallet.charge(
                                new ChargeCommand(wallet, null, Money.of(1_000, USD), null, "dead", "dead-" + UUID.randomUUID()))), pool));
                    }
                }
                futures.add(CompletableFuture.runAsync(() -> scopes.inTenant(world.tenant().id(),
                        () -> deactivate.execute(new dev.ngb.billing.coupon.application.coupon.command.DeactivateCouponUseCase.Command(
                                coupon.id(), true, AUDIT))), pool));
                for (CompletableFuture<Void> future : futures) {
                    future.get(180, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void aWalletCreditCouponCreditsExactlyOnceUnderAParallelRetry() throws Exception {
        World world = worlds.prepaid(0);
        Coupon coupon = Coupon.create(new CouponId(UUID.randomUUID()), world.tenant().id(), "Credit", new Benefit.WalletCredit(Money.of(7_500, USD)),
                Duration.forever(), Scope.everything(),
                new Limits(Instant.parse("2027-01-01T00:00:00Z"), 100L, null, null, null, null), USD, true, "admin", Instant.now());
        inTenant(world, () -> {
            coupons.add(coupon);
            return coupon;
        });
        var command = new dev.ngb.billing.coupon.application.redemption.command.RedeemCouponUseCase.Command(world.wallet().id(), null,
                coupon.id().toString(), "credit-once", AUDIT, null);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<java.util.Optional<Redemption>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return java.util.Optional.of(scopes.inTenant(world.tenant().id(), () -> redeem.execute(command)).redemption());
                    } catch (RuntimeException concurrent) {
                        return java.util.Optional.<Redemption>empty();
                    }
                }, pool));
            }
            List<Redemption> granted = new ArrayList<>();
            for (CompletableFuture<java.util.Optional<Redemption>> future : futures) {
                future.get(120, TimeUnit.SECONDS).ifPresent(granted::add);
            }
            assertThat(granted).isNotEmpty();
            assertThat(granted.stream().map(Redemption::id).distinct().toList())
                    .as("every parallel retry of one key saw the one redemption, never a second").hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(owner.count("select balance from wallets where id = ?", world.wallet().id().value()))
                .as("the wallet was credited exactly once").isEqualTo(7_500);
        assertThat(owner.count("select count(*) from ledger_entries where wallet_id = ?", world.wallet().id().value())).isEqualTo(1);
        assertThat(owner.count("select count(*) from coupon_redemptions where coupon_id = ?", coupon.id().value())).isEqualTo(1);
    }

    @Test
    void anotherTenantCannotChargeAgainstThisCoupon() {
        World mine = worlds.prepaid(100_000);
        World theirs = worlds.prepaid(0);
        Coupon coupon = Coupon.create(new CouponId(UUID.randomUUID()), mine.tenant().id(), "Mine", new Benefit.PercentOff(2_000),
                Duration.forever(), Scope.everything(), Limits.boundedBy(Instant.parse("2027-01-01T00:00:00Z")), USD, true, "admin", Instant.now());
        inTenant(mine, () -> {
            coupons.add(coupon);
            return coupon;
        });

        assertThat(inTenant(theirs, () -> coupons.findById(coupon.id()))).isEmpty();
        assertThat(inTenant(theirs, () -> coupons.lockById(coupon.id()))).isEmpty();
        assertThat(inTenant(theirs, () -> redemptions.lockActiveByCoupon(coupon.id()))).isEmpty();
        assertThat(inTenant(theirs, () -> wallets.find(mine.wallet().id()))).isEmpty();
        inTenant(theirs, () -> chargeWallet.charge(new ChargeCommand(theirs.wallet().id(), null, Money.of(100, USD), null, "x", "cross")));
        assertThat(owner.count("select discount_total from charges where wallet_id = ?", theirs.wallet().id().value()))
                .as("no coupon of another tenant reached this charge").isZero();
    }

    @Test
    void aDeactivatedCouponKeepsDiscountingItsRunningRedemption() {
        World world = worlds.prepaid(100_000);
        Coupon coupon = Coupon.create(new CouponId(UUID.randomUUID()), world.tenant().id(), "Pulled", new Benefit.PercentOff(5_000),
                Duration.forever(), Scope.everything(), Limits.boundedBy(Instant.parse("2027-01-01T00:00:00Z")), USD, true, "admin", Instant.now());
        inTenant(world, () -> {
            coupons.add(coupon);
            return coupon;
        });
        inTenant(world, () -> {
            redemptions.add(Redemption.activate(new RedemptionId(UUID.randomUUID()), world.tenant().id(), coupon.id(), null, world.wallet().id(),
                    world.customerId(), null, null, null, "admin", new ReplayIdentity("deact-" + UUID.randomUUID(), "h"), USD, Instant.now()));
            return coupon;
        });

        var first = scopes.inTenant(world.tenant().id(),
                () -> chargeWallet.charge(new ChargeCommand(world.wallet().id(), null, Money.of(1_000, USD), null, "x", "deact-1")));
        assertThat(first.amount().minorUnits()).isEqualTo(500);

        inTenant(world, () -> {
            Coupon loaded = coupons.lockById(coupon.id()).orElseThrow();
            loaded.deactivate(Instant.now());
            coupons.update(loaded);
            return loaded;
        });

        var second = scopes.inTenant(world.tenant().id(),
                () -> chargeWallet.charge(new ChargeCommand(world.wallet().id(), null, Money.of(1_000, USD), null, "x", "deact-2")));
        assertThat(second.amount().minorUnits()).as("deactivating stops new redemptions, not the one already running").isEqualTo(500);
    }

    @Test
    void aReplayAfterTheCouponWasDeactivatedReturnsTheRecordedCharge() {
        World world = worlds.prepaid(100_000);
        Coupon coupon = Coupon.create(new CouponId(UUID.randomUUID()), world.tenant().id(), "Replay", new Benefit.PercentOff(5_000),
                Duration.forever(), Scope.everything(), Limits.boundedBy(Instant.parse("2027-01-01T00:00:00Z")), USD, true, "admin", Instant.now());
        inTenant(world, () -> {
            coupons.add(coupon);
            return coupon;
        });
        inTenant(world, () -> {
            redemptions.add(Redemption.activate(new RedemptionId(UUID.randomUUID()), world.tenant().id(), coupon.id(), null, world.wallet().id(),
                    world.customerId(), null, null, null, "admin", new ReplayIdentity("replay-" + UUID.randomUUID(), "h"), USD, Instant.now()));
            return coupon;
        });
        ChargeCommand command = new ChargeCommand(world.wallet().id(), null, Money.of(1_000, USD), null, "x", "replay-key");
        var first = scopes.inTenant(world.tenant().id(), () -> chargeWallet.charge(command));

        inTenant(world, () -> {
            Coupon loaded = coupons.lockById(coupon.id()).orElseThrow();
            loaded.deactivate(Instant.now());
            coupons.update(loaded);
            return loaded;
        });

        var replay = scopes.inTenant(world.tenant().id(), () -> chargeWallet.charge(command));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.amount().minorUnits()).as("the recorded result, not a fresh undiscounted charge").isEqualTo(500);
        assertThat(owner.count("select applications_used from coupon_redemptions where coupon_id = ?", coupon.id().value()))
                .as("a replay consumes nothing").isEqualTo(1);
        assertThat(inTenant(world, () -> coupons.findById(coupon.id())).orElseThrow().status()).isEqualTo(CouponStatus.INACTIVE);
    }
}
