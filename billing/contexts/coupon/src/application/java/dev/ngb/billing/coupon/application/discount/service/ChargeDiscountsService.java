package dev.ngb.billing.coupon.application.discount.service;

import dev.ngb.billing.coupon.application.coupon.port.CouponRepository;
import dev.ngb.billing.coupon.application.redemption.port.RedemptionRepository;
import dev.ngb.billing.coupon.application.redemption.service.EndingRedemptions;
import dev.ngb.billing.coupon.domain.coupon.ChargeKind;
import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.discount.ChargeFacts;
import dev.ngb.billing.coupon.domain.discount.DiscountCandidate;
import dev.ngb.billing.coupon.domain.discount.DiscountPlanner;
import dev.ngb.billing.coupon.domain.discount.DiscountQuote;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.billing.coupon.domain.redemption.RedemptionId;
import dev.ngb.billing.coupon.domain.redemption.RedemptionStatus;
import dev.ngb.billing.money.api.charge.ChargeDiscounts;
import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.billing.money.api.charge.DiscountLine;
import dev.ngb.billing.money.api.charge.DiscountRequest;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.runtime.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The coupon context's implementation of the money-owned discount seam. It maps the money port's request onto the
 * pure domain's facts, takes the locks the charge path promises, and records what was consumed.
 *
 * <p>Lock order, which is the whole point of the split: coupon rows that carry a budget first (ascending by id), then
 * the wallet's ACTIVE redemptions (ascending by id). The wallet is already locked by the caller, so no flow here ever
 * locks a wallet after a coupon row.
 */
public class ChargeDiscountsService implements ChargeDiscounts {

    private final CouponRepository coupons;
    private final RedemptionRepository redemptions;
    private final EndingRedemptions endingRedemptions;
    private final Clock clock;

    public ChargeDiscountsService(
            CouponRepository coupons, RedemptionRepository redemptions, EndingRedemptions endingRedemptions, Clock clock) {
        this.coupons = coupons;
        this.redemptions = redemptions;
        this.endingRedemptions = endingRedemptions;
        this.clock = clock;
    }

    @Override
    public dev.ngb.billing.money.api.charge.DiscountQuote preview(DiscountRequest request) {
        List<Redemption> active = redemptions.findActiveByWallet(request.walletId());
        return quoteFor(request, active, budgetsOf(active, false));
    }

    @Override
    public dev.ngb.billing.money.api.charge.DiscountQuote claim(DiscountRequest request) {
        List<Redemption> active = redemptions.lockActiveByWallet(request.walletId());
        return quoteFor(request, active, budgetsOf(active, true));
    }

    @Override
    public void consume(ChargeId chargeId, dev.ngb.billing.money.api.charge.DiscountQuote quote) {
        Instant now = clock.now();
        for (DiscountLine line : quote.lines()) {
            Redemption redemption = redemptions.findById(new RedemptionId(line.redemptionId())).orElse(null);
            if (redemption == null) {
                continue;
            }
            if (!redemptions.recordApplication(redemption.id(), line.amount().minorUnits(), now)) {
                throw new IllegalStateException("the redemption " + redemption.id() + " was no longer consumable");
            }
            Coupon coupon = coupons.findById(redemption.couponId()).orElse(null);
            if (coupon == null) {
                continue;
            }
            if (coupon.limits().budget() != null) {
                coupons.spendBudget(coupon.id(), line.amount().minorUnits());
            }
            endIfExhausted(redemption.id(), now);
        }
    }

    private void endIfExhausted(RedemptionId id, Instant now) {
        Redemption reloaded = redemptions.findById(id).orElse(null);
        if (reloaded == null || reloaded.status() != RedemptionStatus.ACTIVE) {
            return;
        }
        if (!reloaded.hasApplicationsLeft() || !reloaded.hasTotalDiscountLeft() || reloaded.isPastEnd(now)) {
            endingRedemptions.end(reloaded, dev.ngb.billing.coupon.domain.redemption.EndReason.EXHAUSTED, now);
        }
    }

    /** The remaining budget of each coupon on these redemptions that has one; a coupon without one is absent. */
    private Map<CouponId, Money> budgetsOf(List<Redemption> active, boolean lock) {
        Map<CouponId, Money> budgets = new LinkedHashMap<>();
        List<Coupon> withBudget = active.stream()
                .map(redemption -> coupons.findById(redemption.couponId()).orElse(null))
                .filter(coupon -> coupon != null && coupon.limits().budget() != null)
                .sorted(Comparator.comparing(coupon -> coupon.id().value()))
                .toList();
        for (Coupon candidate : withBudget) {
            Coupon coupon = lock ? coupons.lockById(candidate.id()).orElse(candidate) : candidate;
            Money budget = coupon.limits().budget();
            Money remaining = budget.minus(Money.of(coupons.budgetUsed(coupon.id()), budget.currency()));
            budgets.put(coupon.id(), remaining.isNegative() ? Money.zero(budget.currency()) : remaining);
        }
        return budgets;
    }

    private dev.ngb.billing.money.api.charge.DiscountQuote quoteFor(
            DiscountRequest request, List<Redemption> active, Map<CouponId, Money> budgets) {
        if (active.isEmpty()) {
            return dev.ngb.billing.money.api.charge.DiscountQuote.undiscounted(request.gross());
        }
        Map<CouponId, Coupon> byId = new LinkedHashMap<>();
        List<DiscountCandidate> candidates = new ArrayList<>();
        for (Redemption redemption : active) {
            Coupon coupon = coupons.findById(redemption.couponId()).orElse(null);
            if (coupon == null) {
                continue;
            }
            byId.put(coupon.id(), coupon);
            candidates.add(new DiscountCandidate(coupon, redemption, budgets.get(coupon.id())));
        }
        ChargeFacts facts = new ChargeFacts(kind(request.context().kind()), request.context().planId(), request.gross(),
                request.context().metricAmounts());
        DiscountQuote domainQuote = DiscountPlanner.plan(candidates, facts, request.now());
        List<DiscountLine> lines = new ArrayList<>();
        for (var line : domainQuote.lines()) {
            Coupon coupon = byId.get(line.couponId());
            lines.add(new DiscountLine(line.redemptionId().value(), line.couponId().value(), line.couponName(),
                    coupon == null ? "PERCENT_OFF" : coupon.benefit().type(), line.amount()));
        }
        return new dev.ngb.billing.money.api.charge.DiscountQuote(domainQuote.gross(), domainQuote.net(), lines);
    }

    private static ChargeKind kind(dev.ngb.billing.money.api.charge.ChargeKind kind) {
        return switch (kind) {
            case USAGE -> ChargeKind.USAGE;
            case SUBSCRIPTION_RENEWAL -> ChargeKind.SUBSCRIPTION_RENEWAL;
            case RESERVATION -> ChargeKind.RESERVATION;
            case STANDALONE -> ChargeKind.STANDALONE;
        };
    }
}
