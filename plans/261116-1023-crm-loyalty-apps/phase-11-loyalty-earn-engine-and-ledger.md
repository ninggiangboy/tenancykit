---
phase: 11
title: "loyalty earn engine, lots and the order/return API"
status: pending
priority: P1
effort: "L"
dependencies: [10]
---

# Phase 11: loyalty Earn Engine, Lots and the Order/Return API

## Goal

The Tenant's system records an order with its lines and later returns in loyalty, idempotently by its own references, and
each call turns into the right ledger entries; the engine reproduces every vector in `contracts/loyalty/earn-vectors/`.
Members are enrolled by the first order or ahead of it. Nothing here reads, calls or listens to crm.

## Context

The order and return API follows crm's (phase 7) in field names and idempotency rules, so a Tenant gets the same points
whether it sends an order to loyalty directly or forwards crm's `order.*` webhook: same key and body replays the stored
answer, same key and a different body is `409` (`core/core-kernel/.../idempotency/{RepeatableCreate,Fingerprint}`). An
order is immutable once recorded; a mistake is corrected by a return. Each return is its own idempotent call, so loyalty
needs no `orderVersion`, no ordering guarantee and no held-event table: a return that arrives before its order answers
`422 ORDER_UNKNOWN` and the caller retries (a forwarder answers crm's webhook with a non-2xx, and crm's retry schedule
delivers it again). A member row is the first lock of every flow (as the wallet is in Billing:
`docs/concurrency-and-locking.md:7-20`), and the idempotency check runs after it. Migration range `V20`.

## Files to create

- `loyalty/apps/app-boot/src/main/resources/db/migration/V20__points.sql`; regenerate the jOOQ schema
- `loyalty/contexts/points/...` features `member`, `order`, `earn`, `ledger` (domain `EarnCalculator`, application use cases,
  ports, jOOQ persistence, REST controllers)
- Tests: `EarnCalculatorTest`, `EarnVectorTest`, `MemberEnrolIntegrationTest`, `OrderEarnIntegrationTest`,
  `OrderReturnIntegrationTest`, `OrderIsolationTest`

## Tasks

1. **Tables.** `members(tenant_id, id, external_ref, display_name, tier_id, debt, status ACTIVE|ERASED, tier_review_at,
   version, created_at)` where `id` is a UUID v7 loyalty generates and `(tenant_id, external_ref)` is unique (nulls allowed,
   for erased members); `orders(tenant_id, id, order_ref, member_id, currency, occurred_at, store_code, channel, fingerprint,
   created_at)` unique `(tenant_id, order_ref)`; `order_lines(order_id, line_no, sku, category, quantity numeric(12,3),
   amount bigint, earn_eligible, returned_quantity, returned_amount)`; `order_returns(tenant_id, id, order_id, return_ref,
   occurred_at, fingerprint)` unique `(tenant_id, return_ref)` and its lines; `ledger_entries(id, tenant_id, member_id, kind
   EARN|CLAWBACK|REDEEM|REDEEM_REVERSAL|EXPIRE|ERASE, points, order_id, redemption_id, created_at)`; `point_lots(id,
   tenant_id, member_id, entry_id, order_id, earned, remaining, earned_at, expires_at)` with a partial index on `(member_id,
   expires_at, id) where remaining > 0`; `lot_allocations(entry_id, lot_id, points)`; `order_earns(tenant_id, order_id,
   member_id, snapshot jsonb, granted_points, spend_amount, occurred_at)` keyed `(tenant_id, order_id)`. All with RLS; money
   in minor units. loyalty keeps only the line fields earn and returns need (no line `name`). A balance is never stored: it
   is the sum of `remaining` over unexpired lots minus `debt`.
2. **Members.** `PUT /members/{externalRef}` (`loyalty:member:manage`) enrols a member ahead of the first order: `201` on
   create, `200` when it exists, with the same rules for `externalRef` as crm (1-128 characters, trimmed, immutable). An
   order for an unknown `externalRef` creates the member in the same transaction. An `ERASED` member's ref is null, so the
   same ref later starts a new member.
3. **`EarnCalculator`** (pure, in the domain layer). Input: the order's lines with their returned figures and a rules
   snapshot. Output: total points and per-line contributions. `points = roundHALF_EVEN(sum over eligible lines of (amount −
   returned amount) × lineMultiplier ÷ unitAmount)`; `lineMultiplier` is the best (largest) multiplier among the promotions
   that match the line, default 1, times the tier multiplier; decimals with at most two fraction digits, `HALF_EVEN`, the
   rounding taken once at the end. Promotion windows, days of week and time range are evaluated against the order's
   `occurredAt` in the program time zone.
4. **Record an order.** `PUT /orders/{orderRef}` (`loyalty:order:record`). Validate: 1 to the line cap of lines,
   non-negative amounts, positive quantities, `earnEligible` defaulting to `true`, `occurredAt` at most 5 minutes in the
   future. No program yet is `409 PROGRAM_NOT_CONFIGURED`; a currency other than the program's is `422 CURRENCY_MISMATCH`;
   an `ERASED` member cannot be reached by ref, so its old ref enrols a new member. Then lock the member (insert it if
   absent), check the order key (replay `200`, different body `409`), build the snapshot `{unitAmount, currency, tierCode,
   tierMultiplier, lines:[{lineNo, multiplier, promotionIds}]}` from the program, the promotions that are `ACTIVE` now and
   cover `occurredAt`, and the member's tier now, store the order, its lines and its `order_earns` row, and apply the points
   (task 6). `201` with `{orderRef, memberExternalRef, pointsGranted, balance}`.
5. **Record a return.** `PUT /orders/{orderRef}/returns/{returnRef}` (`loyalty:order:record`) with lines `{lineNo, quantity,
   amount}`. Unknown order is `422 ORDER_UNKNOWN`. Lock the member first, then the order; cumulative returned quantity and
   amount may not exceed what was sold (`422 RETURN_EXCEEDS_SALE`); same `returnRef` and body replays, a different body is
   `409`. Update the returned figures, recompute with the stored snapshot (never with current rules) and apply the delta.
   `GET /orders/{orderRef}` (`loyalty:member:read`) shows what the order earned and what each return took back.
6. **Apply.** `delta = desired − granted`. `delta > 0`: repay `debt` first, then create a lot (`earned_at` is the processing
   time, `expires_at` is `expiry_months` later) and an `EARN` entry. `delta < 0`: a `CLAWBACK` consuming the order's own lot
   first and then lots by earliest `expires_at`; any shortfall is added to `debt`. Record `granted_points` and `spend_amount`
   (eligible net amount).
7. **Tier hook.** `points` declares an application port `TierEvaluator` (rise-only check after an earn). The app-boot wiring
   supplies a no-op now; phase 13 supplies the real one by calling `tiers.api`, so `points` never imports `tiers`.
8. **Vectors.** `EarnVectorTest` runs every vector twice: against `EarnCalculator` alone and through the real order and return
   endpoints against a database, comparing the status and ledger delta of each step. Add the cases in
   `contracts/loyalty/earn-vectors/` that the engine exposes as missing, and keep every vector's working in its `note`.

9. **Webhooks.** In the same transaction as the change, write `member.enrolled` (by `PUT /members` or the first order),
   `points.earned` (a positive delta, with the debt it repaid) and `points.clawed-back` (a negative delta, with the debt it
   added) to the outbox, payloads as in `contracts/events/loyalty/`. A replayed order or return, or a delta of 0, writes
   nothing. A test proves each is delivered once, signed and schema-valid, and that a rolled-back request sends nothing.

## Verification

- `cd loyalty && ./gradlew build` green, `EarnVectorTest` green for every vector.
- Tests prove: an order replay grants once; a changed body is `409`; 50 parallel identical `PUT`s create one order and one
  lot; a return before its order is `422` and succeeds once the order exists; two parallel returns of the last unit cannot
  both succeed; a return after a promotion was archived uses the stored snapshot; a return after the points were spent
  leaves a `debt`; the next earn repays the debt before creating a lot; a currency mismatch grants nothing; an order before
  `PUT /program` is `409`; `PUT /members/{ref}` then an order for that ref uses the same member; two tenants' members and
  orders never mix; 50 parallel orders for one member leave the ledger consistent with `Σ granted_points`.

## Risks

- The rules snapshot is the single input of a recompute; a bug that reads current rules breaks reproducibility. The "rule change
  between order and return" vector exists for this.
- Processing-time `earned_at` means an order with an old `occurredAt` grants lots that expire from today; there is no backfill
  in this plan, and `docs/loyalty.md` says so.
- A Tenant that sends each order to crm and to loyalty can see them disagree when one call fails; both APIs are idempotent by
  the same `orderRef`, so the Tenant retries the failed one. `docs/loyalty.md` gives the forwarding and dual-write recipes.
