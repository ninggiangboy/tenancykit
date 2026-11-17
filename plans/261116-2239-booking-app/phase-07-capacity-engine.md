---
phase: 7
title: "Capacity engine"
status: completed
priority: P1
effort: "L"
dependencies: [5]
---

# Phase 7: Capacity Engine

## Goal

A session's places are rows that can be claimed all-or-nothing under heavy contention without ever being claimed twice, and the
count of places a Tenant sees is exactly the rows that are free.

## Context

This is the flash-sale engine. Each place is one `capacity_units` row; a claim is a conditional `UPDATE` over rows picked with
`FOR UPDATE SKIP LOCKED`, so concurrent requests take different rows instead of queueing on one counter. There is no counter column
to drift. The counter design (one row with `held` and `sold`) is built only as a baseline in phase 15's load test and ships in
no production code. This phase can run in parallel with phase 6.

## Files to create

- Migration `V6__capacity_units.sql`: `capacity_units(tenant_id, id, session_id, pool_code, status AVAILABLE|HELD|BOOKED, booking_id,
  held_until)`, a check tying `status` to `booking_id` and `held_until`, index `(session_id, pool_code, status)`, RLS; regenerate jOOQ
- `booking/contexts/inventory/.../capacity/**`: claim, release, confirm, extend, resize pool, remaining
- Implementation of phase 5's "create places" port
- Tests: `CapacityClaimConcurrencyTest`, `CapacityResizeTest`, `CapacityReclaimTest`

## Tasks

1. **Create places.** When a session or pool is created, insert `capacity` rows with `generate_series` in one statement; a pool with
   no places cannot exist.
2. **Claim.** For `(session, pool, qty, booking)`: pick `qty` `AVAILABLE` rows `FOR UPDATE SKIP LOCKED` and set `HELD`, `booking_id`,
   `held_until`. If fewer than `qty` rows were returned, roll the claim back and answer `409 sold-out` with the remaining count. If
   short, reclaim lapsed holds of that pool once (`HELD` with `held_until + grace < now` back to `AVAILABLE`) and retry once.
3. **Confirm / release / extend.** Confirm moves the booking's `HELD` rows to `BOOKED` with a conditional update that checks the row
   count; release returns them to `AVAILABLE`; extend moves `held_until`.
4. **Resize.** Increase inserts rows; decrease deletes only `AVAILABLE` rows and answers `409` if the target is below held plus
   booked. Deleting a session with held or booked places is refused.
5. **Remaining.** `remaining = count(AVAILABLE)` per pool; exposed on the session read model, subject to the offering's
   `public_shows_remaining` for the gate.
6. **Quantity.** A request's `qty` is capped by the offering's `max_qty`; a request for more than the pool's capacity is `422`.
7. **Deterministic row choice** is not required; tests must not depend on which rows are chosen.

## Verification

- 1,000 parallel claims of 1 place on a 100-place pool produce exactly 100 holds and 900 `sold-out`, repeated 20 times; the sum of
  `HELD` and `BOOKED` never exceeds capacity in any sampled moment.
- A claim of 3 places with only 2 free changes nothing (all-or-nothing).
- Resize rules hold: grow adds free places, shrink below held-plus-booked is `409`.
- A lapsed hold is reclaimed by the lazy path and by phase 8's worker without double release.

## Risks

- If throughput still collapses at high contention, the real limit is the connection pool, not the rows; phase 13's admission control
  caps in-flight claims per session, phase 14's waiting room orders arrivals, and phase 15 measures it all.
