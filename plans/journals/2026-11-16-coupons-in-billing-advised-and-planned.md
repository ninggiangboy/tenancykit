---
title: Coupons in billing advised and planned
date: 2026-11-16
summary: "Generic coupons applied inside money's ChargeUseCase through a money-owned ChargeDiscounts port; 8-phase plan validated"
---

# Coupons in billing advised and planned

## What happened

Advised and planned Coupons for billing (plan `plans/261116-0326-coupon-in-billing/`, 8 phases, validated). The scout
showed every charge funnels through `ChargeUseCase`, so the discount is applied there once, not at invoice time and not in
each caller.

## Decision

- New `coupon` context; `money` owns a small `ChargeDiscounts` port (preview, claim, consume) that `coupon` implements, so there is no cycle.
- `Charge.amount` stays the net the wallet paid; gross is `amount + discount_total`, with discount lines in a money-owned table.
- A fully discounted charge is a succeeded Charge of 0: no ledger entry, no invoice line.
- Reservation from an estimate holds the gross amount; plan and metric scoped coupons never apply to captures or standalone charges; deactivating a coupon leaves running redemptions unless `revokeRedemptions` is set.
- Admin and Tenant System may both create and redeem; every coupon needs a total bound.

## Findings worth keeping

- Charge, invoice line, reservation capture and ledger entry all require a positive amount, which a 100% discount would break.
- `contracts/openapi/billing*.yaml` are generated from the controllers; permission catalog and event schemas are hand-written.
- Renewal is not distinguishable in money today, so callers pass a `ChargeContext`.

## Next steps

Run `/ak-cook plans/261116-0326-coupon-in-billing/plan.md`. Open check in phase 4: confirm `CreditWallet` can re-lock a wallet already locked inside the redeem transaction.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
