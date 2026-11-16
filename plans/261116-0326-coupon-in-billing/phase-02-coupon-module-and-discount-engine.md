---
phase: 2
title: "Phase 2: Coupon module and discount engine"
status: done
priority: P1
effort: "L"
dependencies: []
---

# Phase 2: Coupon module and discount engine

## Goal

Create the `coupon` Gradle module and its pure domain: coupon, promotion code, redemption and the discount engine that
decides what a set of redemptions takes off a charge, with no database and no framework.

## Context

Layers are separate source sets (`api`, `domain`, `application`, `adapter`), so the domain cannot see Spring or other
contexts (`billing/build-logic/src/main/kotlin/layered-module-conventions.gradle.kts`). The domain therefore defines its
own input type for "the facts of a charge" and the application layer (phase 4 and 5) maps `money.api.ChargeContext`
onto it. Use `contexts/webhook/build.gradle.kts` and its source layout as the model for a small context.

## Files

- Modify: `billing/settings.gradle.kts` (add `contexts:coupon`)
- Create: `billing/contexts/coupon/build.gradle.kts` (plugin `layered-module-conventions`; adapter dependencies: jOOQ starter,
  `libs:jooq-schema`, web MVC, Jackson; domain and application see `libs:shared-kernel` through the convention)
- Create under `billing/contexts/coupon/src/domain/java/dev/ngb/billing/coupon/domain/`:
  - `coupon/`: `Coupon`, `CouponId`, `Benefit` (sealed: `PercentOff(basisPoints)`, `AmountOff(Money)`, `WalletCredit(Money)`),
    `Duration`, `TimeWindow`, `Scope`, `ChargeKind`, `Limits`, `CouponStatus`, `PromotionCode`, `PromotionCodeId`
  - `redemption/`: `Redemption`, `RedemptionId`, `RedemptionStatus`, `EndReason`
  - `discount/`: `DiscountPlanner`, `ChargeFacts`, `DiscountQuote`, `AppliedDiscount`, `Eligibility`
- Create tests mirroring those packages under `billing/contexts/coupon/src/test/java/dev/ngb/billing/coupon/domain/`

## Rules the code must encode

All of them are in the plan's Design table; the points that are easy to get wrong:

1. A coupon is valid only with at least one total bound: `redeemBy`, `maxRedemptions` or `budget`.
2. Percent is basis points 1 to 10,000. `AmountOff` and `WalletCredit` are positive and carry a currency.
3. A duration with no limit is forever. `TimeWindow` is a count of days or months from `redeemedAt`, in UTC.
4. Each discount is rounded once (`HALF_UP`), then capped by: per-application maximum, the redemption's remaining total
   cap, the coupon's remaining budget, the running net. The running net never goes below 0.
5. Candidate selection for stacking: the stackable set together, and each non-stackable coupon alone; lowest net wins;
   ties go to the earliest redemption, then the lowest id. The result must not depend on the order the redemptions are
   passed in.
6. `ChargeFacts` carries kind, optional plan id, gross, and a metric-to-amount map that stays empty until phase 7.
   A coupon with a plan or metric filter does not match facts that lack a plan.
7. `DiscountPlanner` takes the remaining budget per coupon as an input; it never reads anything.
8. `Redemption` transitions to ENDED in the same call that consumes the last application or the last of the cap, or when
   `now` reaches `endsAt`. REVOKED is terminal.

## Steps

1. Add the module to `settings.gradle.kts` and create `build.gradle.kts`; run the module's compile.
2. Write the value types and validation, one test class each for constructor invariants.
3. Write `DiscountPlanner` and its table-driven tests: the 100,000 → 70,000 example and its reverse order, 100% off,
   fixed amount larger than the charge, cap by total, cap by budget, minimum charge not met, stackable and exclusive
   mixes, expired and exhausted redemptions.
4. Add randomized invariant tests with a fixed seed and at least 10,000 cases: the net is between 0 and the gross, the
   discounts sum to gross minus net, no cap is exceeded, shuffling the input gives the same result.
5. Run the architecture tests that cover the new module's packages (tests must mirror production packages).

## Todo

- [x] Module registered and compiling
- [x] Value types with invariants and tests
- [x] `DiscountPlanner` with golden tests
- [x] Randomized invariant tests
- [x] Domain has no dependency outside `api`, shared-kernel and the JDK

## Verification

- `cd billing && ./gradlew :contexts:coupon:test`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*ArchitectureTest'` (the module is not wired into boot yet; this
  proves the existing gates still pass)

## Risks

- The stacking rule is the most debatable: keep it small and fully covered by tests, so changing it later is one class.
- Money arithmetic on `long` minor units versus `BigDecimal`: use `BigDecimal` for the percent step only, and return
  `long`.

## Security considerations

None in the domain. Validation here is the first line against a Tenant System creating an unbounded coupon.
