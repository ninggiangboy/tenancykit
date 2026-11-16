package dev.ngb.billing.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.Application;
import dev.ngb.billing.coupon.application.coupon.port.CouponRepository;
import dev.ngb.billing.coupon.application.redemption.port.RedemptionRepository;
import dev.ngb.billing.coupon.domain.coupon.Benefit;
import dev.ngb.billing.coupon.domain.coupon.ChargeKind;
import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.Duration;
import dev.ngb.billing.coupon.domain.coupon.Limits;
import dev.ngb.billing.coupon.domain.coupon.Scope;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.billing.coupon.domain.redemption.RedemptionId;
import dev.ngb.billing.invoicing.application.subscription.command.CreateSubscriptionUseCase;
import dev.ngb.billing.invoicing.application.subscription.command.RenewSubscriptionUseCase;
import dev.ngb.billing.invoicing.domain.subscription.Subscription;
import dev.ngb.billing.money.MoneyTestWorld;
import dev.ngb.billing.money.MoneyTestWorld.World;
import dev.ngb.billing.pricing.PricingTestWorld;
import dev.ngb.billing.pricing.api.plan.BillingCycle;
import dev.ngb.billing.pricing.application.rating.command.EstimateCostUseCase;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.support.OwnerDatabase;
import dev.ngb.billing.support.PostgresContainerConfig;
import dev.ngb.billing.support.ScopeTestSupport;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** A coupon on a renewal, and an estimate that names a wallet: the two callers that supply a plan. */
@SpringBootTest(classes = Application.class)
@Import(PostgresContainerConfig.class)
class CouponCallerIntegrationTest {

    private static final Currency USD = Currency.of("USD");
    private static final Audit AUDIT = new Audit("user/test", "req-1");
    private static final long PRICE = 2_900;
    private static final java.time.Instant DAY = java.time.Instant.parse("2026-10-01T00:00:00Z");

    @Autowired MoneyTestWorld worlds;
    @Autowired PricingTestWorld pricing;
    @Autowired CouponRepository coupons;
    @Autowired RedemptionRepository redemptions;
    @Autowired CreateSubscriptionUseCase createSubscription;
    @Autowired RenewSubscriptionUseCase renew;
    @Autowired EstimateCostUseCase estimates;
    @Autowired dev.ngb.billing.pricing.application.rating.command.ChargeFromUsageUseCase chargeUsage;
    @Autowired ScopeTestSupport scopes;
    @Autowired UnitOfWork unitOfWork;
    @Autowired OwnerDatabase owner;

    private <T> T inTenant(World world, java.util.function.Supplier<T> work) {
        return scopes.inTenant(world.tenant().id(), () -> unitOfWork.transactional(work));
    }

    private Coupon coupon(World world, Benefit benefit, Scope scope, boolean stackable) {
        Coupon coupon = Coupon.create(new CouponId(UUID.randomUUID()), world.tenant().id(), "Promo", benefit, Duration.forever(), scope,
                new Limits(Instant.parse("2027-01-01T00:00:00Z"), null, null, null, null, null), USD, stackable, "admin", Instant.now());
        inTenant(world, () -> {
            coupons.add(coupon);
            return coupon;
        });
        return coupon;
    }

    private void attach(World world, Coupon coupon) {
        Redemption redemption = Redemption.activate(new RedemptionId(UUID.randomUUID()), world.tenant().id(), coupon.id(), null, world.wallet().id(),
                world.customerId(), null, null, null, "admin", new ReplayIdentity("attach-" + UUID.randomUUID(), "h"), USD, Instant.now());
        inTenant(world, () -> {
            redemptions.add(redemption);
            return redemption;
        });
    }

    private Subscription subscribe(World world) {
        return subscribe(world, plan(world));
    }

    private UUID plan(World world) {
        return pricing.plan(world.tenant().id(), "pro", pricing.pro(), BillingCycle.MONTHLY, null, null).id().value();
    }

    private Subscription subscribe(World world, UUID planId) {
        return scopes.inTenant(world.tenant().id(), () -> createSubscription.execute(
                new CreateSubscriptionUseCase.Command(world.wallet().id(), planId, "sub-" + UUID.randomUUID(), AUDIT, null))).subscription();
    }

    private void aMonthPasses(Subscription subscription) {
        owner.jdbc().update("update subscriptions set anchor = anchor - interval '1 month', next_renewal_at = next_renewal_at - interval '1 month' where id = ?",
                subscription.id().value());
    }

    private long balance(World world) {
        return owner.jdbc().queryForObject("select balance from wallets where id = ?", Long.class, world.wallet().id().value());
    }

    @Test
    void aRenewalIsDiscountedAndRecordsWhatTheWindowActuallyPaid() {
        World world = worlds.prepaid(100_000);
        attach(world, coupon(world, new Benefit.PercentOff(2_000), Scope.everything(), true));
        Subscription subscription = subscribe(world);
        aMonthPasses(subscription);

        scopes.inTenant(world.tenant().id(), renew::runDue);

        long discounted = PRICE - (PRICE * 2_000 / 10_000);
        assertThat(balance(world)).as("the first period and the renewal are both discounted").isEqualTo(100_000 - 2 * discounted);
        Map<String, Object> renewal = owner.jdbc().queryForMap(""" 
                select amount, paid_amount from subscription_events where subscription_id = ? and type = 'RENEWED'
                order by occurred_at desc, id desc limit 1""", subscription.id().value());
        assertThat(((Number) renewal.get("amount")).longValue()).as("the history keeps the gross").isEqualTo(PRICE);
        assertThat(((Number) renewal.get("paid_amount")).longValue()).as("and records what was paid").isEqualTo(discounted);
    }

    @Test
    void aRenewalScopedCouponAppliesAndASourceScopedOneForUsageDoesNot() {
        World usageOnlyWorld = worlds.prepaid(100_000);
        attach(usageOnlyWorld, coupon(usageOnlyWorld, new Benefit.PercentOff(5_000), new Scope(Set.of(ChargeKind.USAGE), Set.of(), Set.of()), true));
        Subscription subscription = subscribe(usageOnlyWorld);
        aMonthPasses(subscription);

        scopes.inTenant(usageOnlyWorld.tenant().id(), renew::runDue);

        assertThat(balance(usageOnlyWorld)).as("a renewal is not a usage charge").isEqualTo(100_000 - 2 * PRICE);
    }

    @Test
    void aPlanScopedCouponAppliesToThePlanItNamesOnly() {
        World world = worlds.prepaid(100_000);
        UUID planId = plan(world);
        attach(world, coupon(world, new Benefit.PercentOff(5_000), new Scope(Set.of(), Set.of(planId), Set.of()), true));
        Subscription subscription = subscribe(world, planId);
        aMonthPasses(subscription);

        scopes.inTenant(world.tenant().id(), renew::runDue);

        long discounted = PRICE - (PRICE * 5_000 / 10_000);
        assertThat(balance(world)).isEqualTo(100_000 - 2 * discounted);
    }

    @Test
    void aUsageChargeIsDiscountedAndItsRatingKeepsTheGross() {
        World world = worlds.prepaid(1_000_000);
        attach(world, coupon(world, new Benefit.PercentOff(2_000), Scope.everything(), true));
        String metric = pricing.metric();
        var plan = pricing.plan(world.tenant().id(), "metered", new dev.ngb.billing.pricing.domain.rule.EventBased(
                metric, "call", new java.math.BigDecimal("1000")), BillingCycle.MONTHLY, null, null);
        pricing.record(world.tenant().id(), world.customerId(), metric, "call", "10", DAY);

        var result = scopes.inTenant(world.tenant().id(), () -> chargeUsage.execute(
                new dev.ngb.billing.pricing.application.rating.command.ChargeFromUsageUseCase.Command(
                        world.customerId(), world.wallet().id(), plan.id(), null, DAY, DAY.plus(1, java.time.temporal.ChronoUnit.DAYS))));

        // 10 calls at 1,000 minor units is 10,000; 20% off leaves 8,000.
        assertThat(result.rating().amount().minorUnits()).as("the rating keeps the gross").isEqualTo(10_000);
        assertThat(balance(world)).isEqualTo(1_000_000 - 8_000);
        assertThat(owner.count("select discount_total from charges where tenant_id = ? and source = 'USAGE'", world.tenant().id().value()))
                .isEqualTo(2_000);
    }

    @Test
    void anEstimateWithoutAWalletIsTheGrossAndWithOneItIsTheNet() {
        World world = worlds.prepaid(100_000);
        attach(world, coupon(world, new Benefit.PercentOff(2_000), Scope.everything(), true));
        var planId = plan(world);

        var plain = scopes.inTenant(world.tenant().id(), () -> estimates.estimate(new EstimateCostUseCase.Request(new dev.ngb.billing.pricing.domain.plan.PricingPlanId(planId), null, null, Map.of())));
        var discounted = scopes.inTenant(world.tenant().id(), () -> estimates.estimate(
                new EstimateCostUseCase.Request(new dev.ngb.billing.pricing.domain.plan.PricingPlanId(planId), null, null, Map.of(),
                        world.wallet().id(), null)));

        assertThat(plain.discount()).isNull();
        assertThat(discounted.discount()).isNotNull();
        assertThat(discounted.discount().lines()).singleElement().satisfies(line ->
                assertThat(line.amount().minorUnits()).isEqualTo(plain.quote().amount().minorUnits() * 2_000 / 10_000));
        assertThat(discounted.discount().net().minorUnits()).isLessThan(plain.quote().amount().minorUnits());
        assertThat(discounted.discount().gross()).isEqualTo(plain.quote().amount());
    }
}
