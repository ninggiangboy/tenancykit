---
phase: 6
title: "Phase 6: Callers, scope and estimate"
status: done
priority: P1
effort: "M"
dependencies: [4, 5]
---

# Phase 6: Callers, scope and estimate

## Goal

Make usage rating and subscription renewal pass their context so source and plan scoped coupons work, show the net paid
on renewals, and let an estimate show the discounted amount while a reservation from an estimate still holds the gross.

## Context (verified in source)

- `ChargeFromUsageUseCase` builds `new ChargeCommand(walletId, null, amount, "usage-set:" + setKey, "plan:" + code + "@v" + n,
  "rating:" + setKey + ":" + attempt, caller)` and records a `UsageRating` with the gross `quote.amount()`.
- `RenewSubscriptionUseCase` charges `price.get().amount()` with `SubscriptionCharges.reference(...)` and records the
  amount in history and in `subscription.renewed`; period 0 is reported as a renewal too.
- `EstimateCostUseCase.Request` has no wallet; `ReserveFromEstimateUseCase` reserves `estimate.quote().amount()` and
  validates the wallet's currency.
- `subscription_events` rows record `amount`; old rows have no paid amount.

## Files

- Modify (pricing): `ChargeFromUsageUseCase` (context `USAGE` with the plan id), `EstimateCostUseCase` (optional wallet and
  a discount preview), `ReserveFromEstimateUseCase` (holds the gross), `RatingController` (request and response fields),
  and their tests
- Modify (invoicing): `RenewSubscriptionUseCase` (context `SUBSCRIPTION_RENEWAL` with the plan id; paid amount from the
  charge result), `SubscriptionHistoryEntry`, `SubscriptionEvents`, `JooqSubscriptionRepository` and the subscription controller
  response if it exposes history
- Create: `V29__subscription_paid_amount.sql` (nullable `paid_amount bigint` on `subscription_events`); regenerate jOOQ
- Modify: `contracts/events/billing/subscription.renewed.schema.json` (optional `paidAmount`), `usage.rated` schema only if
  its description needs the gross/net clarification; regenerate the OpenAPI files
- Modify (money): the HTTP charge request maps to `ChargeContext.STANDALONE` or `RESERVATION`; the capture path already does
- Modify: `ArchitectureRules.TABLE_OWNER` only if a new table appears (it does not)

## Behaviour

1. Usage charge: gross is the rated amount; the charge result's net is what the wallet paid. The rating keeps the gross
   and the breakdown; its status is CHARGED even when the net is 0.
2. Renewal: gross is the plan period price; the history entry keeps the gross as `amount` and adds `paid_amount` from the
   charge result; the event carries both (`amount` stays the gross price, `paidAmount` is new and optional). A renewal
   whose net is 0 still renews. A renewal whose gross is 0 still skips the charge exactly as today.
3. Estimate: an optional `walletId` (checked with the same visibility rule as reserve) adds `grossAmount`, `amount` after
   discounts and the lines, using `preview` (no locks, nothing consumed). Without a wallet the response is unchanged.
4. Reserve from estimate holds the **gross** amount, so a coupon that ends between estimate and capture can never make the
   capture exceed the hold; the capture applies discounts at capture time and returns the remainder. This was
   validated with the user (gross, 2026-11-16).
5. Scope: source filters work for all four paths; plan filters work for usage and renewal; standalone and reservation
   captures carry no plan, so a plan scoped coupon never applies to them (validated with the user, 2026-11-16).

## Steps

1. Thread `ChargeContext` through the usage and renewal use cases; update their unit tests with a fake `ChargeDiscounts`.
2. Add `paid_amount` through migration, history, event and repository; regenerate jOOQ and the event schema.
3. Add the estimate preview and the reserve-from-estimate behaviour; update `RatingController` and regenerate the OpenAPI files.
4. Integration tests: a usage charge and a renewal with `SAVE20` + `WELCOME10K` both net 70,000; a source-scoped coupon
   applies only to renewals; a plan-scoped coupon applies to the right plan only; an estimate with a wallet shows the
   discount and an estimate without one does not; reserve from estimate then capture after the coupon was deactivated
   works and returns the remainder.

## Todo

- [x] Usage charge passes context
- [x] Renewal passes context, `paid_amount` stored and published
- [x] Estimate preview with optional wallet
- [x] Reserve from estimate holds the gross
- [x] Source and plan scope tests
- [x] Contracts regenerated

## Verification

- `cd billing && ./gradlew :contexts:pricing:test :contexts:invoicing:test`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*Subscription*' --tests '*Rating*' --tests '*Estimate*' --tests '*Coupon*' --tests '*OpenApiContractTest' --tests '*JooqSchemaTest'`

## Risks

- Pricing now calls the discount port during an estimate: it must stay read-only (`preview` takes no locks).
- Consumers of `subscription.renewed` that sum `amount` will over-count discounted renewals; the docs and the schema
  description must say `paidAmount` is what was taken from the wallet.

## Security considerations

An estimate with a wallet reveals that wallet's coupon effect, so it applies the same customer visibility rule as the
other wallet endpoints; a Tenant System cannot preview another system's customer.
