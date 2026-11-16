package dev.ngb.billing.pricing.application.rating.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.customer.api.lookup.CustomerVisibility;
import dev.ngb.billing.money.api.charge.ChargeContext;
import dev.ngb.billing.money.api.charge.ChargeDiscounts;
import dev.ngb.billing.money.api.charge.ChargeKind;
import dev.ngb.billing.money.api.charge.DiscountQuote;
import dev.ngb.billing.money.api.charge.DiscountRequest;
import dev.ngb.billing.money.api.wallet.WalletLookup;
import dev.ngb.billing.money.api.wallet.WalletSummary;
import dev.ngb.billing.pricing.application.plan.port.PlanRepository;
import dev.ngb.billing.pricing.application.rating.service.PlanSelection;
import dev.ngb.billing.pricing.domain.metric.Measure;
import dev.ngb.billing.pricing.domain.metric.UsageSummary;
import dev.ngb.billing.pricing.domain.plan.PlanStatus;
import dev.ngb.billing.pricing.domain.plan.PlanVersion;
import dev.ngb.billing.pricing.domain.plan.PricingPlan;
import dev.ngb.billing.pricing.domain.plan.PricingPlanId;
import dev.ngb.billing.pricing.domain.plan.Quote;
import dev.ngb.billing.sharedkernel.id.WalletId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Prices expected usage without recording anything. It is a pure calculation from a plan version and
 * quantities, using the same rules and the same single rounding as the real charge, so an estimate and the
 * charge for identical input are the same amount.
 *
 * <p>When the request names a wallet, the estimate also shows what that wallet would actually pay after its coupons,
 * through the same read-only preview the charge uses: it takes no lock and consumes nothing.
 */
public class EstimateCostUseCase {

    /**
     * {@code version} and {@code at} are optional: by default the version in force now. {@code usage} maps metric to the
     * expected measure. {@code walletId} and {@code caller} are optional: with a wallet the estimate is discounted.
     */
    public record Request(PricingPlanId planId, Integer version, Instant at, Map<String, Measure> usage, WalletId walletId, UUID caller) {
        public Request(PricingPlanId planId, Integer version, Instant at, Map<String, Measure> usage) {
            this(planId, version, at, usage, null, null);
        }
    }

    /** {@code discount} is null when no wallet was named; otherwise it carries the net and the lines. */
    public record Estimate(PricingPlan plan, PlanVersion version, Quote quote, DiscountQuote discount) {}

    private final UnitOfWork unitOfWork;
    private final PlanRepository plans;
    private final WalletLookup wallets;
    private final CustomerVisibility visibility;
    private final ChargeDiscounts discounts;
    private final Clock clock;

    public EstimateCostUseCase(
            UnitOfWork unitOfWork, PlanRepository plans, WalletLookup wallets, CustomerLookup customers, ChargeDiscounts discounts, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.plans = plans;
        this.wallets = wallets;
        this.visibility = new CustomerVisibility(customers);
        this.discounts = discounts;
        this.clock = clock;
    }

    public Estimate estimate(Request request) {
        return unitOfWork.transactional(() -> {
            PricingPlan plan = plans.findById(request.planId()).orElseThrow(() -> new NotFoundException("pricing plan not found"));
            if (plan.status() == PlanStatus.RETIRED) {
                throw new ConflictException("the plan is retired");
            }
            PlanVersion version = PlanSelection.choose(plan, request.version(), request.at() == null ? clock.now() : request.at());
            Quote quote = version.quote(plan.currency(), summary(version, request.usage()));
            DiscountQuote discount = preview(request, plan, quote);
            return new Estimate(plan, version, quote, discount);
        });
    }

    /** The wallet's coupon effect on the estimate, or null when no wallet was named. */
    private DiscountQuote preview(Request request, PricingPlan plan, Quote quote) {
        if (request.walletId() == null) {
            return null;
        }
        WalletSummary wallet = wallets.find(request.walletId()).orElseThrow(() -> new NotFoundException("wallet not found"));
        visibility.requireVisible(wallet.customerId(), request.caller(), () -> new NotFoundException("wallet not found"));
        if (!wallet.currency().equals(plan.currency())) {
            throw new IllegalArgumentException("the wallet is in %s but the plan is priced in %s".formatted(wallet.currency(), plan.currency()));
        }
        if (!quote.amount().isPositive()) {
            return DiscountQuote.undiscounted(quote.amount());
        }
        return discounts.preview(new DiscountRequest(wallet.id(), quote.amount(),
                new ChargeContext(ChargeKind.USAGE, plan.id().value(), quote.metricAmounts()), clock.now()));
    }

    /** Rejects a metric the version does not price, because silently ignoring it would understate the estimate. */
    private static UsageSummary summary(PlanVersion version, Map<String, Measure> usage) {
        Map<String, String> priced = version.rule().metrics();
        Map<String, Measure> checked = new HashMap<>();
        for (Map.Entry<String, Measure> entry : usage.entrySet()) {
            if (!priced.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("metric '" + entry.getKey() + "' is not priced by this plan");
            }
            checked.put(entry.getKey(), entry.getValue());
        }
        return new UsageSummary(checked);
    }
}
