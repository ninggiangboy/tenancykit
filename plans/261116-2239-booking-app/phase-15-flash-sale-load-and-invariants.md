---
phase: 15
title: "Flash-sale load and invariant checks"
status: pending
priority: P1
effort: "L"
dependencies: [14]
---

# Phase 15: Flash-Sale Load and Invariant Checks

## Goal

Booking's claim that nothing is oversold or double-booked is shown by measurement: a flash-sale run through the public gate ends
with exactly the capacity held, p95 and p99 are reported, and an invariant check finds no violation after the run.

## Context

Billing's load test is a Gradle task (`ops/loadtest/run.sh` runs `:apps:app-boot:loadTest`); booking follows it. The target shape is
100,000 attempts against a 5,000-place session. The numbers below are initial targets calibrated in this phase and then written into
`docs/booking.md`: p95 ≤ 150 ms and p99 ≤ 400 ms at 500 requests per second against one 5,000-place session on the compose
Postgres, measured end to end through the gate. A counter-row baseline (one row, `held + sold + qty <= capacity`) is measured beside
the one-row-per-place design, as a test fixture only; it is not production code.

## Files to create

- `booking/apps/app-boot/src/loadTest/**`: a runner (Java HTTP client on virtual threads), scenarios `flash-sale-capacity`,
  `appointment-contention`, `multi-resource-crossing`
- `ops/loadtest/booking.sh`
- `booking/apps/app-boot/src/loadTest/resources/baseline-counter.sql` (fixture)
- `booking/apps/app-boot/.../health/InvariantCheck.java` and an `invariants` CLI entry (`ops/smoke/booking-invariants.sh`)
- Tests: `InvariantCheckTest`

## Tasks

1. **Invariant check.** SQL that proves: no capacity pool has `HELD + BOOKED > capacity`; no unit is owned by a booking that is not
   `HELD`/`CONFIRMED`; no booking is `HELD` or `CONFIRMED` without its claims; no two `interval_claims` overlap on one resource; no
   `HELD` booking is more than `grace + job interval` past expiry. Exit code non-zero on a violation, one line per violation.
2. **Flash-sale scenario.** Seed one session with 5,000 places and `queue_mode = REQUIRED`; fire 100,000 customers through `/p/`: enter
   the queue, poll with the advised `retryAfter`, hold when admitted; confirm a share; let the rest expire; wait for the worker. Report
   holds, `sold-out`, `429`, queue admission order, polling request rate and the database reads it caused, errors, p50, p95, p99 of the
   hold call, and the invariant result. Run it once with `queue_mode = NONE` too, so the cost of the queue is on record.
3. **Contention scenarios.** 100 simultaneous requests for one popular appointment slot; crossing multi-resource sets. Each ends with
   the invariant check.
4. **Baseline.** Run the same flash-sale against the counter-row fixture and print both curves, so the choice of one row per place is
   recorded with evidence, not belief.
5. **Calibrate and record.** Set the final p95 target, the connection pool size and the admission cap; write them in
   `docs/booking.md` with the machine description; if the target is missed, tune (admission cap, pool, batch size) before changing
   the design, and record what was tried.

## Verification

- `ops/loadtest/booking.sh` ends with `holds == 5000`, zero invariant violations, and prints the p95 and p99 table.
- Repeating the run three times gives the same invariant result.
- The baseline run is reported next to it, and the report says which design was faster and by how much.

## Risks

- A laptop and CI differ: the report names the machine, and the target is a regression threshold, not a promise.
- If `SKIP LOCKED` rows still queue, the connection pool is the limit; the admission cap is then the control, and that is documented.
