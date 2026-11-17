---
phase: 8
title: "Booking lifecycle: hold, confirm, extend, cancel, expire"
status: completed
priority: P1
effort: "L"
dependencies: [6, 7]
---

# Phase 8: Booking Lifecycle: Hold, Confirm, Extend, Cancel, Expire

## Goal

A Tenant System holds a place or a range for a customer, confirms it after its own payment, extends or cancels it, and lapsed
holds are released by the worker, with no orphan claim and exactly one winner when confirm races expiry.

## Context

`Booking` is the one aggregate; it starts `HELD` (the hold). The Tenant holds, charges through Billing, then confirms; booking never
calls Billing. Confirm is accepted while the booking is still `HELD` and its claims are still held, even slightly past
`hold_expires_at` (the grace window), and rejected with `409 hold-lapsed` once the worker has expired it; `extend` lets a Tenant
lengthen a hold once before charging. Idempotency uses `Idempotency-Key` with `RepeatableCreate`/`Fingerprint`. Locks follow
booking row first, then resources by ascending id, then units by ascending id (`docs/concurrency-and-locking.md` pattern).

## Files to create

- Migration `V7__bookings.sql`: `bookings(tenant_id, id, offering_id, customer_ref, status, qty, session_id null, pool_code null,
  starts_at null, ends_at null, source TENANT|GATE|WAITLIST, hold_expires_at, extended_seconds, confirmed_at, cancelled_at,
  policy_code null, series_id null, version, created_at)` with RLS, keyset indexes, unique `(tenant_id, idempotency scope)`;
  regenerate jOOQ
- `booking/contexts/booking/src/{domain,application,api,adapter}/...`: aggregate, state machine, use cases, controller
- `booking/apps/app-boot/.../jobs/ExpireHoldsJob.java`
- Outbox publisher port `BookingEvents` with the booking-state events defined here (`booking.held`, `booking.confirmed`,
  `booking.cancelled`, `booking.expired`)
- Tests: `BookingLifecycleIntegrationTest`, `ConfirmExpireRaceTest`, `BookingIdempotencyTest`, `ExpireHoldsJobTest`, `BookingIsolationTest`

## Tasks

1. **State machine.** `HELD → CONFIRMED`, `HELD → CANCELLED`, `HELD → EXPIRED`, `CONFIRMED → CANCELLED`; each transition is a
   conditional update `WHERE status = :from` and bumps `version`.
2. **Create.** `POST /bookings` with `offeringId`, `customerRef`, then `sessionId` + `pool` + `qty` (capacity) or `start` +
   `duration` + resource choices (interval). Picks the engine from the offering kind, claims, writes the booking and appends
   `booking.held` in one transaction. TTL is the offering's `hold_ttl_seconds` or the setting.
3. **Confirm.** `POST /bookings/{id}/confirm`: allowed while `HELD` and `now ≤ hold_expires_at + grace`; asks the engine to confirm its
   claims, which clears `expires_at`/`held_until`; appends `booking.confirmed`. Idempotent per key. After the worker expired it: `409
   hold-lapsed`.
4. **Extend.** `POST /bookings/{id}/extend {seconds}` once, total extension capped by settings; moves the claim expiry in the engine.
5. **Cancel.** `POST /bookings/{id}/cancel`: releases claims, records `cancelled_at`; the cancellation policy code is computed in
   phase 10, a plain `NONE` here. Cancelling a `HELD` booking appends `booking.cancelled` too.
6. **Expire job.** `ExpireHoldsJob` under `TenantSweep`: pick `HELD` bookings with `hold_expires_at + grace < now` `FOR UPDATE SKIP LOCKED`,
   conditionally move them to `EXPIRED`, release claims, append `booking.expired`. Batch-bounded and lease-guarded like other jobs.
7. **Reads.** `GET /bookings/{id}` and `GET /bookings` through the list query contract (filters: status, offering, customerRef,
   source, range).
8. **Typed problems.** `409 slot-taken`, `409 sold-out`, `409 hold-lapsed`, `422 outside-open-hours`, `409 booking-state` for an
   illegal transition.
9. **Permissions.** `booking:booking:hold` (create, extend, cancel a hold), `booking:booking:confirm`, `booking:booking:read`,
   `booking:booking:manage` (cancel confirmed).

## Verification

- 100 parallel confirms of one booking succeed once, the rest are idempotent replays or `409 booking-state`.
- Confirm racing the expire job: exactly one transition wins; after it, claims are held iff the booking is `CONFIRMED`; a SQL
  invariant check finds no claim whose booking is not `HELD` or `CONFIRMED`.
- With a frozen clock: confirm just inside the grace window succeeds, just outside is `409 hold-lapsed`.
- The same `Idempotency-Key` with the same body returns the same booking; with a different body is `409`.
- Tenant B cannot read or act on tenant A's booking.

## Risks

- A Tenant that charges after a long delay loses the place: mitigated by `extend`, grace, the typed error, and the documented refund
  recipe; the risk is accepted and written in `docs/booking.md`.
