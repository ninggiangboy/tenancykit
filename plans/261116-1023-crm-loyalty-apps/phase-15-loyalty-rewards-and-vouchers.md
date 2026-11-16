---
phase: 15
title: "loyalty rewards and vouchers"
status: pending
priority: P1
effort: "L"
dependencies: [12]
---

# Phase 15: loyalty Rewards and Vouchers

## Goal

A Tenant keeps a catalog of rewards; a member exchanges points for a reward and receives a single-use voucher; the POS validates
and uses the voucher exactly once.

## Context

A reward redemption spends points through the one spend function on `points.api` (phase 12) and issues a voucher in the same
transaction. Lock order: member first (inside the spend), then the reward row for stock. Voucher use locks only the voucher row,
so it cannot form a cycle with the member lock. Migration range `V60`. Voucher codes are bearer tokens used only on
authenticated Tenant System calls; there is no public voucher endpoint.

## Files to create

- `loyalty/apps/app-boot/src/main/resources/db/migration/V60__rewards.sql`; regenerate the jOOQ schema
- `loyalty/contexts/rewards/...` features `reward`, `voucher` (domain, application, api, adapter); the app-boot adapter that
  implements `RedemptionReversalHook`
- Tests: `RewardApiIntegrationTest`, `RewardRedemptionIntegrationTest`, `VoucherUseConcurrencyTest`, `VoucherReversalTest`

## Tasks

1. **Tables.** `rewards(tenant_id, id, name, description, points_cost, stock, status ACTIVE|ARCHIVED, valid_from, valid_to,
   voucher_valid_days, version)` (`stock` null means unlimited); `vouchers(tenant_id, id, code, reward_id, member_id,
   redemption_id, status ISSUED|USED|VOIDED, issued_at, expires_at, used_at, use_ref)` unique `(tenant_id, code)`. RLS on both.
2. **Catalog.** `GET|POST /rewards`, `GET|PATCH /rewards/{id}` (`If-Match`): name, description, cost, stock, validity window,
   `voucher_valid_days`, archive. A cost or stock change never affects vouchers already issued.
3. **Redeem a reward.** `POST /members/{externalRef}/reward-redemptions` with `Idempotency-Key` and `{rewardId}`. In one
   transaction: read the reward (status, window, cost) without a lock; call the spend function (member lock, debt and balance
   checks, allocation); decrement `stock` with `update ... set stock = stock - 1 where id = ? and stock > 0` and fail the whole
   transaction with `409 OUT_OF_STOCK` if no row changed; create the voucher with a random 12-character Crockford base32 code
   (retry on a code collision) valid until `now + voucher_valid_days`. Replays by key return the same voucher.
4. **Use a voucher.** `POST /vouchers/{code}/use` with `{useRef}`: one conditional update `ISSUED → USED` where `expires_at` is
   in the future. The same `useRef` on a used voucher replays `200`; a different `useRef` is `409 VOUCHER_USED`; an expired
   voucher is `409 VOUCHER_EXPIRED`; an unknown or another tenant's code is a constant `404`. `GET /vouchers/{code}` and `GET
   /members/{ref}/vouchers?status=` read them, with `rewardId`; expiry is derived from `expires_at` at read time, with no job.
5. **Reversal hook.** `RedemptionReversalHook` (declared in phase 12) is implemented here: reversing a reward redemption voids
   an `ISSUED` voucher and returns one unit of stock; a `USED` voucher refuses the reversal with `409 VOUCHER_ALREADY_USED`.
   The hook runs inside the reversal's transaction, after the member lock, before points are restored.
6. **Permissions.** `loyalty:reward:read`, `loyalty:reward:manage`, `loyalty:reward:redeem`, `loyalty:voucher:read`,
   `loyalty:voucher:use`.

7. **Webhooks.** `voucher.issued` in the reward redemption's transaction, `voucher.used` on the `ISSUED → USED` update
   (a `useRef` replay writes nothing) and `voucher.voided` with `REVERSAL` from the reversal hook. The voucher id, not the
   code, is the key a Tenant should derive other systems' idempotency keys from; `docs/loyalty.md` says so.

## Verification

- `cd loyalty && ./gradlew build` green.
- Tests prove: 100 parallel uses of one voucher succeed once; 100 parallel redemptions of a reward with stock 10 issue exactly
  10 vouchers and spend points exactly 10 times (the failures roll back their spend); the same `Idempotency-Key` issues one
  voucher; reversing an unused voucher returns the points and the stock; reversing a used one is refused and changes nothing;
  an expired voucher cannot be used; tenant B cannot see or use tenant A's code.
- A deadlock check: a loop of redemptions, reversals and uses over the same member and voucher for 30 seconds ends without a
  lock-wait error.

## Risks

- Stock and points must roll back together: the test forces a failure after the spend and asserts no `REDEEM` entry remains.
- Codes are guessable only by brute force against an authenticated, rate-limited API; the token endpoint's limiter and the
  suite's per-credential limits apply, and `docs/loyalty.md` says codes are not secrets to log.
