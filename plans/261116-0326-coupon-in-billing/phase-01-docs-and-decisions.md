---
phase: 1
title: "Phase 1: Docs and decisions"
status: done
priority: P1
effort: "S"
dependencies: []
---

# Phase 1: Docs and decisions

## Goal

Write the coupon behaviour down as the maintained contract before any code exists, so every later phase builds to
the same words, and record the architecture decision.

## Context

Topic documents in `docs/` are the maintained contract and executable owners (schemas, migrations, tests) take over
details later (`docs/README.md`, "Authority"). The OpenAPI files are **generated** from controllers, so they are not
written by hand here; the event schemas and permission catalog arrive with the code that emits or enforces them.

## Files

- Create: `docs/coupons.md` (stay under the 800-line limit; link to the generated contracts instead of copying them)
- Modify: `docs/README.md` (reading-order row), `docs/overview-and-scope.md` (vocabulary: Coupon, Promotion code, Redemption),
  `docs/architecture.md` (decision ledger entry: money-owned discount port implemented by `coupon`; module list),
  `docs/wallet-ledger-and-charging.md` (Charge gross and net, fully discounted charge, zero capture),
  `docs/pricing-and-usage.md` (estimate with a wallet), `docs/invoicing-and-subscriptions.md` (net lines, zero-net
  charges skipped, `paidAmount`), `docs/webhooks-and-events.md` (new and extended events),
  `docs/concurrency-and-locking.md` (new rows in the lock-order table)

## Steps

1. Read each file above before editing it.
2. Write `docs/coupons.md` from the plan's Outcome contract and Design sections: vocabulary, benefits, duration,
   scope, limits and the mandatory total bound, redemption lifecycle (ACTIVE, ENDED, REVOKED), stacking and order with
   the 100,000 → 70,000 example, where the discount sits in the charge path, zero-net charges, estimate, permissions,
   events, lock order, what a Tenant System may do, and the non-goals.
   Add a section "Granting a coupon from another system" with the loyalty voucher as the example (D10 in the plan): the
   Tenant System handles loyalty's `voucher.issued` webhook, maps the voucher's `rewardId` to a coupon id or code in its own
   store, grants with `Idempotency-Key: loyalty-voucher:<voucherId>`, then uses the voucher with that redemption as
   `useRef`; Billing's `coupon.redeemed` and `coupon.redemption-ended` webhooks tell it when the benefit starts and ends; when Billing refuses for good it reverses
   the loyalty reward redemption; it checks before the member spends points that the coupon is active and the wallet has no
   ACTIVE redemption of it. Name both grant routes and when to pick each, and say that a coupon's total bound and `redeemBy`
   should cover the reward's stock and voucher validity.
3. State plainly in `docs/coupons.md` that plan and metric scoped coupons apply only to charges whose caller supplies
   the plan and metrics (usage and renewal), and document the four validated answers from the plan's validation log (hold the gross on estimate reservations; zero-net charges; no plan or metric scope on capture and standalone; deactivate keeps running redemptions unless revoked).
4. Add the entry to the decision ledger in `docs/architecture.md` with the rejected alternatives: discount at the
   invoice, discount in `pricing`, one apply per caller, Stripe-side coupons.
5. Update the other topic documents with the smallest accurate edits. Do not describe behaviour that the plan does not
   include.
6. Check every relative link resolves.

## Todo

- [x] `docs/coupons.md` written
- [x] README row and vocabulary added
- [x] Decision ledger entry added with rejected alternatives
- [x] Charge, estimate, invoicing, events and locking documents updated
- [x] Links verified

## Verification

- `grep -n "coupons.md" docs/README.md` finds the row; each new relative link opens.
- `wc -l docs/coupons.md` is under 800.
- `git diff --stat` touches only `docs/` and nothing else.

## Risks

Docs drift from the code that follows. Mitigated by the final phase, which re-checks each claim against source.

## Security considerations

None beyond stating that tenant isolation and the mandatory total bound are requirements.
