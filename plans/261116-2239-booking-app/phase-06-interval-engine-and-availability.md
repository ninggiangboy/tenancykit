---
phase: 6
title: "Interval engine and availability"
status: pending
priority: P1
effort: "L"
dependencies: [5]
---

# Phase 6: Interval Engine and Availability

## Goal

A resource's time can be claimed as a range with no overlap guaranteed by the database, and a Tenant can ask for the free start
times of an interval offering in a window.

## Context

The engine serves appointments, rentals and tables. It promises no overlap, not flash-sale throughput. PostgreSQL's exclusion
constraint (`EXCLUDE USING gist`) with `btree_gist` enforces it; ranges are half-open `[start, end)` so back-to-back bookings
are legal. The constraint cannot see `expires_at`, so a claim first removes the same resource's lapsed holds in the same
transaction. Availability is computed at query time from weekly hours, exceptions, blocks and existing claims; it is never
materialized. This phase can run in parallel with phase 7 (disjoint context files).

## Files to create

- Migration `V5__interval_claims.sql`: `interval_claims(tenant_id, id, resource_id, booking_id, during tstzrange, expires_at null)`,
  `EXCLUDE USING gist (tenant_id WITH =, resource_id WITH =, during WITH &&)`, RLS, index `(booking_id)`; regenerate jOOQ
- `booking/contexts/inventory/src/{domain,application,api,adapter}/...`: port `InventoryEngine` (shared with phase 7), feature
  `interval` (claim, release, extend, confirm), feature `availability` (free slots)
- Tests: `IntervalClaimConcurrencyTest`, `IntervalExpiryReclaimTest`, `AvailabilityTest`, `AvailabilityDstTest`

## Tasks

1. **Port.** `InventoryEngine` with `claim(ClaimRequest) -> Claim | Conflict`, `confirm(bookingId)`, `release(bookingId)`,
   `extend(bookingId, until)`, keyed by `booking_id`. Both engines implement it; the port states which promise each makes.
2. **Claim.** Compute the claimed range as `[start - bufferBefore, end + bufferAfter)`. Delete the resource's claims whose
   `expires_at + grace < now` that overlap the range, then insert; map SQLSTATE `23P01` to `slot-taken`. A confirmed claim has
   `expires_at` null.
3. **Availability.** `GET /offerings/{id}/availability?from&to&resource=` returns start instants on the offering's `slot_step`
   grid: inside open hours (weekly hours, replaced by an `OPEN` exception, cut by `CLOSED` exceptions and blocks), with the
   service range `[start, start + duration)` inside open time (buffers may overhang), start ≥ now + lead time, ≤ horizon, and the
   buffered range free of claims. For several requirements, a start is free only if every requirement has a free resource. Result
   is keyset-paged by start instant.
4. **Durations.** `duration` is chosen by the caller within `[duration_min, duration_max]` on `duration_step`; a fixed-duration
   service has min = max.
5. **Time safety.** Open hours are resolved per local date in the resource's zone, so a day with 23 or 25 hours, or a 30-minute shift,
   is correct; availability uses the recurrence port from phase 5 for blocks.
6. **No engine endpoint is public**: claims are made only by the booking lifecycle (phase 8). This phase exposes availability and
   test-only claim helpers.

## Verification

- 50 parallel claims of overlapping ranges on one resource leave exactly one row; adjacent ranges both succeed; the same range on
  two resources both succeed.
- With a frozen clock, a hold past `expires_at + grace` no longer blocks the range, and one inside the window still does.
- Availability on a spring-forward day offers no start inside the gap and keeps the intended local hours; a `CLOSED` exception and a
  block each remove their slots; lead time and horizon cut as stated.
- `cd booking && ./gradlew build` green; the OpenAPI contract test covers availability.

## Risks

- Gist exclusion plus `FOR UPDATE` interplay under crossing resource sets is the deadlock source for phase 9; the lock order is
  fixed there. Here a claim touches one resource only.
