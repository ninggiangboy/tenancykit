---
phase: 7
title: "Phase 7: Metric scope"
status: done
priority: P2
effort: "M"
dependencies: [6]
---

# Phase 7: Metric scope

## Goal

Let a coupon be limited to named metrics, by giving the discount engine the amount each metric contributed to a usage
charge. This is the costliest piece and the only one that needs pricing's line breakdown; it is a separate phase so it
can be reviewed, reordered or released on its own. Phases 1 to 6 ship without it.

## Context (verified in source)

- `Quote.lines()` is a list of `PriceLine(description, metric, quantity, exact amount)`; a usage charge covers several
  metrics at once, and the final amount is rounded once and then bounded by the minimum and maximum charge.
- A metric scoped coupon should discount only what its metrics cost, but the charge total may have been replaced by the
  minimum or maximum charge.

## Rule (the planner's decision; keep it small and fully tested)

1. Pricing builds `metricAmounts`: for each metric, the sum of its lines' exact amounts, rounded `HALF_UP` to minor units.
   Lines with no metric (a start fee, a base) belong to no metric.
2. For a metric scoped coupon, the base is `min(running net, remaining subtotal of the coupon's metrics)`. Percent and
   fixed amount are computed on that base, then capped as in the main rules.
3. When a metric scoped discount is applied, it reduces the remaining subtotal of the coupon's metrics pro rata to what is
   left on each, using floor division with the remainder given to the first metric in alphabetical order, so the result is
   deterministic and the subtotals never go negative.
4. Unscoped coupons act on the running net and do not reduce any metric subtotal.
5. When the minimum or maximum charge replaced the calculated amount, the metric subtotals are scaled down only if they
   sum to more than the gross; they are never scaled up.
6. Charges whose caller cannot supply metrics (standalone, reservation capture, renewal) never match a metric scoped
   coupon.

## Files

- Modify (coupon domain): `DiscountPlanner`, `ChargeFacts`, `Scope` and tests (golden cases and the randomized invariants
  extended with metric subtotals)
- Modify (pricing): `ChargeFromUsageUseCase` (build `metricAmounts` from `quote.lines()`), `EstimateCostUseCase` (same, for
  a wallet preview), and tests
- Modify: `CouponChargeDiscounts` (map `ChargeContext.metricAmounts` onto `ChargeFacts`)
- Modify: `docs/coupons.md` (the rule above, in words and with one example)

## Steps

1. Extend the engine and its tests first (no other module changes).
2. Build `metricAmounts` in pricing; unit-test against a usage plan with two metrics and a minimum charge.
3. Integration test: a coupon scoped to metric A discounts only A's share of a two-metric usage charge; two metric scoped
   coupons on the same metric never discount it by more than its subtotal; a metric scoped coupon does not apply to a
   renewal.

## Todo

- [x] Engine and golden tests for metric scope
- [x] Randomized invariants still hold with metric subtotals
- [x] Pricing supplies `metricAmounts` for charges and estimates
- [x] Integration test with two metrics and a minimum charge
- [x] Docs updated

## Verification

- `cd billing && ./gradlew :contexts:coupon:test :contexts:pricing:test`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*Coupon*' --tests '*Rating*'`

## Risks

- The pro-rata and clamping rules are easy to get subtly wrong; the randomized invariants (net within 0 to gross, discounts
  sum to gross minus net, input order irrelevant, no subtotal below zero) are the safety net.
- If a simpler rule is acceptable later ("a metric scoped percent coupon discounts its metrics' subtotal, once, and
  coupons never share a pool"), it removes step 3 of the rule; that is a decision for the user, not an implementation shortcut.

## Security considerations

None specific; the amounts come from the server's own rating, never from a client.
