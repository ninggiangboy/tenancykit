---
phase: 13
title: "Smoke, isolation and load test"
status: pending
priority: P1
effort: "2d"
dependencies: [5, 7, 8, 9, 10, 12]
---

# Phase 13: Smoke, isolation and load test

## Goal

Prove the whole path end to end with scripts anyone can run, prove the broker grants are minimal, and measure the
connection and latency targets.

## Context

- `ops/smoke/` holds the existing proofs: `notification-smoke.sh` (grant a user, wait for the replica, assert one Mailpit
  message and one in-app row), `isolation.sh` (refusals and positive controls per broker user), `suite-smoke.sh`,
  `console-smoke.sh`, and `lib/` helpers.
- `ops/loadtest/` has `run.sh` and `booking.sh` for the existing load scenarios.

## Requirements

1. `ops/smoke/realtime-smoke.sh` runs against the compose stack and asserts, with exit code 1 on any miss:
   1. a stream opened for a user receives `notification.created` within 2 seconds of a role grant, with the right
      `unread` value;
   2. a stream opened without the billing read permission receives no billing signal while one with it does (trigger a
      billing event the existing seed scripts can cause);
   3. a user of another tenant never receives either signal;
   4. a disconnect then reconnect with `Last-Event-ID` receives the missed signals in order;
   5. a reconnect with an id older than the replay window receives one `resync`, and a following REST call returns the
      current truth;
   6. changing the user's permissions closes the stream and the reconnect carries the new permission set;
   7. a platform-scope user at `platform.localhost` receives their own access signal.
2. `ops/smoke/isolation.sh` extended: for every producer user (`notification`, `billing`, `survey`, `booking`, `customer`,
   `loyalty`, `workflow`) a positive control (may publish to `realtime.commands`) and refusals (may not publish to
   `platform.events`, another app's exchange, `realtime.fanout`, or declare `realtime.commands`); for `realtime` the
   refusal to write any app exchange and any other queue's read.
3. `ops/loadtest/realtime.sh`: ramps SSE connections to 1,000 and then 5,000 distributed over many users and tenants,
   sends signals at a steady rate plus a 1,000-per-second burst for one user, and reports connection count, p50/p95/p99
   delivery latency (from the command's `occurredAt` to the client receive time), memory and CPU of a realtime replica,
   and `resync` counts. Targets: at least 1,000 stable connections (goal 5,000), p95 under 1 second, no connection
   resets from the proxy over a 10-minute hold.
4. The suite smoke (`suite-smoke.sh`) calls the realtime smoke so CI and local runs exercise it.
5. Findings (numbers, tuning) recorded in a short report under `plans/reports/`; any tuning change is made in the owning
   phase's files and listed.

## Files

Create: `ops/smoke/realtime-smoke.sh`, `ops/loadtest/realtime.sh` (plus a small client helper if shell is not enough,
kept under `ops/loadtest/`), a report under `plans/reports/`.

Modify: `ops/smoke/isolation.sh`, `ops/smoke/suite-smoke.sh`, `ops/smoke/lib/*` if a helper is missing.

## Steps

1. Write the smoke with a tiny SSE client (curl `--no-buffer` is enough for the assertions; use a script helper that
   parses `id` and `event` lines and enforces timeouts).
2. Run it against the full stack; fix the owning phase's defects, never loosen an assertion.
3. Extend `isolation.sh` using its existing positive-control and refusal helpers for each producer and for realtime.
4. Write the load test; first run at 1,000 connections, then raise to 5,000; capture numbers and the Caddy and JVM
   settings used.
5. Add the smoke call to `suite-smoke.sh` and the CI step if the suite workflow runs the smoke.
6. Write the report: results versus targets, tuning applied, and anything left.

## Todo

- [ ] `realtime-smoke.sh` with the seven assertions
- [ ] Isolation assertions for seven producers and realtime
- [ ] `realtime.sh` load test and its first run at 1,000
- [ ] Run at 5,000 and capture limits
- [ ] Suite smoke integration
- [ ] Report written

## Verification

- `ops/smoke/realtime-smoke.sh` and `ops/smoke/isolation.sh` exit 0 against a fresh `docker compose up -d --build --wait`.
- The load report shows at least 1,000 stable connections and p95 delivery below 1 second; the 5,000 result is recorded
  honestly even if it falls short.
- A deliberately removed grant makes `isolation.sh` fail (shown once, then restored).

## Risks

- Seeding a billing event from scripts may need a Tenant System credential; reuse `ops/seed/billing.sh`.
- A shared dev machine may not reach 5,000 sockets; the report states the machine and the limit found.

## Security

- Scripts read secrets from `.env` and never print tokens.
- The isolation proof is the evidence for the "write only `realtime.commands`" claim in the docs.

## Next

Phase 14.
