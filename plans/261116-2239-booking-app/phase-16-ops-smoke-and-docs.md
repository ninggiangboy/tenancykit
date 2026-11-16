---
phase: 16
title: "Ops, end-to-end smoke and docs"
status: pending
priority: P1
effort: "M"
dependencies: [10, 15]
---

# Phase 16: Ops, End-to-End Smoke and Docs

## Goal

The whole app runs on the compose stack, a smoke script playing a Tenant System proves the main paths, isolation is proven, and the
docs describe what exists.

## Context

The sample Tenant System does not integrate booking (it integrates Billing only); the smoke script plays the Tenant, as for the other
apps. Docs count what exists with `grep`, not what plans promise. Docs impact: new user-visible behavior, setup, commands and
architecture, so `docs/` is updated; plans and reports are not evergreen authority.

## Files to create or edit

- `ops/smoke/booking.sh` (and a smoke-only webhook forwarder in compose, with `allowNonPublicTargets` enabled for that service only)
- `ops/smoke/isolation.sh` (booking checks), `docker-compose.yml` smoke wiring
- `docs/booking.md` finalized; `docs/README.md`, `docs/overview-and-scope.md`, `docs/suite-messaging.md`, `docs/runtime-profiles.md`,
  `docs/security-and-reliability.md` (public gate limits, token keys, rotation), `README.md`
- `booking/README.md`, `contracts/openapi/booking.yaml` regenerated from the running app
- Journal entry under `plans/journals/`

## Tasks

1. **Smoke script.** On the compose stack: enable `booking` for a tenant, create a resource, hours, an interval offering and a capacity
   offering with a weekly series; hold through the gate with a minted customer token; confirm as the Tenant and receive the signed
   webhook through the forwarder; fill a 2-place session through the waiting room (enter the queue, poll until admitted, hold); join the waitlist; cancel one booking and receive `waitlist.offered`; reschedule
   a booking; create a series; run `booking-invariants.sh` at the end.
2. **Isolation.** `ops/smoke/isolation.sh` proves the booking broker user cannot publish to or bind to another app's exchange and that
   `booking_app` cannot read another database.
3. **Stop and restart.** With booking stopped, Billing, mailroom, survey, attribution, crm and loyalty behave as before; with the worker
   stopped, holds still work and lapse when it returns.
4. **Docs.** Write `docs/booking.md` from the code: engines and their promises, state machine, lock order, the confirm-after-payment
   recipe with Billing, recurrence rules and the chosen library, settings with defaults, the gate and its per-instance limits, the
   measured load numbers, operations (jobs, keys, rotation, invariant check) and non-goals. Update the suite docs and verify each claim
   against source or a run.
5. **Final grep gates.** Re-run the Done-means greps from plan.md and attach their output to the journal.
6. **Journal.** Record the library choice, the calibration, anything that diverged from the plan.

## Verification

- `ops/smoke/booking.sh` exits 0 on a clean stack; `ops/smoke/isolation.sh` exits 0.
- Every Done-means item in `plan.md` is checked against a command or file and passes.
- Links in the edited docs resolve; `git grep -n -i "booking" -- docs` shows the app in every document that counts apps.

## Risks

- Documenting plans instead of code: every statement is verified against source or the smoke run before it is written.
