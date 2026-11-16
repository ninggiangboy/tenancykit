---
phase: 12
title: "loyalty balance, redemption, reversal and debt"
status: pending
priority: P1
effort: "L"
dependencies: [11]
---

# Phase 12: loyalty Balance, Redemption, Reversal and Debt

## Goal

The POS reads a member's points, spends them and reverses a spend, with no double spend under concurrency and a clear refusal
while the member is in debt.

## Context

Redemption is a direct spend plus an idempotent reversal, not hold and commit. The member row is locked first and the
idempotency key is read after that lock (the wallet rule in `docs/concurrency-and-locking.md`, "Idempotency"). Lots are
consumed by earliest `expires_at`, then id. Migration range `V30`. The member endpoints are keyed by the POS's `externalRef`.

## Files to create

- `loyalty/apps/app-boot/src/main/resources/db/migration/V30__redemptions.sql`; regenerate the jOOQ schema
- `loyalty/contexts/points/...` features `redemption`, `balance` (use cases, ports, persistence, controllers) and the spend
  function exposed on `points.api` for the rewards context
- Tests: `RedemptionApiIntegrationTest`, `RedemptionConcurrencyTest`, `BalanceApiIntegrationTest`, `DebtIntegrationTest`

## Tasks

1. **Table** `redemptions(id, tenant_id, member_id, idempotency_key, fingerprint, points, reference, status ACTIVE|REVERSED,
   created_at, reversed_at)` unique `(tenant_id, idempotency_key)`, with RLS.
2. **Balance.** `GET /members/{externalRef}?expiringWithinDays=` (default 30, at most 365): `available` (sum of `remaining`
   over lots with `expires_at` in the future, minus `debt`; negative while the member is in debt), `debt`, `tier` (code, name,
   multiplier), `nextTier` (filled by phase 13, `null` until then), `expiringSoon` as lots grouped by `expires_at` within the
   window. Unknown ref is `404 MEMBER_NOT_FOUND`; an `ERASED` member is `404`.
3. **Ledger.** `GET /members/{externalRef}/ledger` newest first, keyset pagination, with the kind, points, order or redemption
   reference and time.
4. **Redeem.** `POST /members/{externalRef}/redemptions` with `Idempotency-Key` (required, `400` without) and body `{points,
   reference}`. Lock the member; look the key up (same key and body replays `200`, same key and a different body is `409`);
   refuse with `409 MEMBER_IN_DEBT` while `debt > 0` and `409 INSUFFICIENT_POINTS` when `points` exceeds `available`; consume
   unexpired lots by earliest `expires_at`, then id, writing `lot_allocations` and one `REDEEM` entry; answer `201` with the
   redemption and the new `available`.
5. **Reversal.** `POST /members/{externalRef}/redemptions/{id}/reversal`: lock the member first; an already reversed
   redemption replays `200`; otherwise add each allocation back to its lot, write a `REDEEM_REVERSAL` entry and mark the
   redemption reversed. Declare a port `RedemptionReversalHook` (phase 15 implements it for vouchers). Points restored into a lot
   that has expired in the meantime stay expired; documented.
6. **Spend on `points.api`.** A function used by phase 15: it runs inside the caller's transaction, takes the member lock,
   performs the same checks and allocation and returns the redemption id. One implementation serves both callers.
7. **Permissions.** `loyalty:member:read` for balance and ledger, `loyalty:points:redeem` for redeem and reversal.

8. **Webhooks.** `points.redeemed` on a new redemption and `points.redemption-reversed` on a reversal, in the same
   transaction; a replay writes nothing. The reward redemption of phase 15 also writes `points.redeemed`, with the voucher's
   redemption id as `redemptionId`.

## Verification

- `cd loyalty && ./gradlew build` green.
- Tests prove: 100 parallel redemptions of 1 point against 50 available succeed exactly 50 times and fail the rest with
  `INSUFFICIENT_POINTS`; 100 parallel calls with one `Idempotency-Key` spend once; a spend racing a clawback leaves
  `available` equal to the sum of lots minus debt; a member in debt is refused with `MEMBER_IN_DEBT` and can redeem after
  the next earn repays it; reversal restores the exact allocations and replays safely; an expired-but-unswept lot is neither
  counted nor spent; tenant B cannot see or redeem tenant A's member.
- Run `RedemptionConcurrencyTest` 20 times in a row to catch lock-order flakiness.

## Risks

- Hot members serialize on their row lock; acceptable, the transaction is short and holds nothing else.
- The balance is a sum over lots: the partial index keeps it cheap, and a test with 5,000 lots for one member asserts the
  query stays within a fixed time.
