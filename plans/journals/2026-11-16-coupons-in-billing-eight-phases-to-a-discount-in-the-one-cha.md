---
title: "Coupons in Billing: eight phases to a discount in the one charge path"
date: 2026-11-16
summary: "A new coupon context with percent, amount and wallet-credit benefits, scoped and stacked, applied through a money-owned port inside ChargeUseCase; ledger, invoices and replay keep their meaning."
---

# Coupons in Billing: eight phases to a discount in the one charge path

## What happened

Implemented the whole coupons plan, one commit per phase:

1. `docs/coupons.md` plus the vocabulary, decision-ledger and topic-document edits, written before any code.
2. The `contexts:coupon` Gradle module and the pure `DiscountPlanner` (percent-before-amount ordering, stackable-set-vs-each-exclusive candidate selection, caps and floor 0), with golden cases and 10,000-case seeded invariants.
3. `V27__coupons.sql`, the guarded counters (`redemptions_count`, `budget_used`, a conditional `recordApplication`) and the jOOQ adapters, with row-level security from the first migration.
4. The management and redeem API: three permissions, two events with schemas, and the same-404 rule for unknown/inactive/expired/exhausted codes.
5. The money-owned `ChargeDiscounts` port and the seam in `ChargeUseCase` (lock, key, claim, charge net, add with lines, consume, publish), the gross/line columns on a Charge, and the zero-net path.
6. Charge context through usage rating and renewal, `paid_amount` on renewals, and a discount preview on an estimate that names a wallet.
7. Metric-scoped coupons: a subtotal base, pro-rata reduction, and scaling when pricing capped the gross.
8. Concurrency, isolation, the event contract and the suite smoke step.

## Decision

The seam is the design. Money owns `ChargeDiscounts` in its `api` source set; coupon implements it, so money depends on nothing in coupon and the arrow never turns back. A discount is applied exactly once, in the one place every charge already passes through, which is why pricing's single rounding, the append-only ledger, the invoice-as-net and idempotent replay all kept their meaning instead of being reimplemented per caller.

Two invariants did real work. A failed charge records no discount lines and consumes nothing, so a retry after a top-up is the first time the coupon is spent. A replay returns the stored charge before the claim is asked, so a charge replayed after its coupon was deactivated is correct rather than freshly undiscounted.

## Things that caught bugs

- A Charge whose lines-sum invariant applied to failed charges too: the invariant belongs only to a succeeded charge, since a failure keeps a gross it never discounted.
- Redeem locked the code before the coupon, inverting the documented wallet-coupon-code order. The unlocked read now only names the coupon to lock.
- A `WalletLookup` that grew a `lockById` method broke lambda fakes and gave two beans of one type; the lock became its own `LockWallet` port served by its own service.
- A coupon code is case-insensitive, so the replay fingerprint hashes the stored form: retyping `save20` for `SAVE20` is the same request, not a conflict.

## Verification

`./gradlew build` in `billing/` is green: architecture, Modulith, jOOQ drift, OpenAPI contract, permission catalog and the event contract all pass. The four charge paths each have an end-to-end test that nets 70,000 on a gross 100,000 with SAVE20 then WELCOME10K. Twenty parallel charges across wallets sharing one budget never overspend it; twenty parallel redeems of a coupon with one left yield exactly one. No `git diff` under `platform/`, `tenant/`, `mailroom/`, `survey/`, `attribution/`, `notification/` or `mailer/`; `core/` gained only the two event constants.

## Next steps

- The load check on a hot budgeted coupon (phase 8 item 6) was not run on the compose stack: the budget row is a shared lock by design and the numbers are not yet recorded.
- Portal coupon entry stays a non-goal, so a customer still redeems through the API or the Tenant's own UI.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
