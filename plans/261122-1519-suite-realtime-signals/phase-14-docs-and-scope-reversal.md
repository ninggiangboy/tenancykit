---
phase: 14
title: "Docs and scope reversal"
status: pending
priority: P2
effort: "1d"
dependencies: [13]
---

# Phase 14: Docs and scope reversal

## Goal

The documentation states what now exists, records the dated reversal of the "real-time SSE stream" non-goal, and no
document still contradicts the new behaviour.

## Context

Places that call realtime a non-goal or describe topology:
`docs/notification.md` (the "is not … a real-time stream" row), `docs/overview-and-scope.md` (several non-goal lines,
including the SMS/push/Slack/Telegram/real-time SSE bullet and the Attribution "stream in real time" clause, which is a
different meaning and must be judged separately), `docs/suite-messaging.md` (topology table and prose),
`docs/runtime-profiles.md`, `docs/architecture.md`, `docs/README.md`, `README.md` (service list, ports, routes),
`contracts/messaging/README.md`, `contracts/commands/README.md`, `contracts/events/README.md`.

## Requirements

1. A new `docs/realtime.md` (under 800 lines): purpose, runtimes, broker items, command and stream contract, audience and
   permission rule, replay and `resync`, coalescing parameters and where they are configured, operations (health,
   metrics, dead-letter runbook, forcing a replica rebuild, what to check when a user sees no live updates), security and
   the publisher-asserted tenant trust boundary, retention, load-test results summary.
2. `docs/overview-and-scope.md`: a dated reversal (2026-11-22) moving "real-time SSE stream" from non-goal to scope for
   Platform and Tenant users only; customers stay out of scope and the reason is stated; leave the other non-goals alone.
3. `docs/notification.md`: remove "real-time stream" from the not-column, add the `notification.created` signal and
   a pointer to `docs/realtime.md`.
4. `docs/suite-messaging.md`: add `realtime.commands`, `realtime.fanout` and the realtime queues to the topology, the
   rule that producers hold `write` on `realtime.commands` only, and that realtime binds to no app exchange.
5. `README.md`, `docs/runtime-profiles.md`, `docs/architecture.md`, `docs/README.md`: service list, route `/realtime/*`,
   port, runtime table, diagram nodes, docs index.
6. Contracts READMEs: the `realtime.push` row and where the no-signal registry lives.
7. Verify every link and every factual claim against source, tests, scripts or live state before finishing; where a claim
   rests on the load test, cite the report and the numbers.

## Files

Create: `docs/realtime.md`.
Modify: `docs/overview-and-scope.md`, `docs/notification.md`, `docs/suite-messaging.md`, `docs/runtime-profiles.md`,
`docs/architecture.md`, `docs/README.md`, `README.md`, `contracts/messaging/README.md`, `contracts/commands/README.md`,
`contracts/events/README.md`, `contracts/realtime/README.md` (created in phase 10).

## Steps

1. Read each target document completely before editing (do not edit from memory).
2. Write `docs/realtime.md` from the implemented behaviour, not from this plan; copy numbers only from the load report.
3. Apply the edits above; keep each edit to the smallest owning surface and link to scripts or schemas instead of copying
   their details.
4. Search for leftovers: `grep -rn -i "real-time\|realtime\|real time" docs README.md contracts` and judge each hit.
5. Check links with the repository's docs check if one exists, otherwise a link scan script run once.
6. Record the dated reversal text in the PR description as well.

## Todo

- [ ] `docs/realtime.md`
- [ ] Dated reversal in overview and scope
- [ ] Notification doc updated
- [ ] Suite messaging topology updated
- [ ] Runtime profiles, architecture, README, docs index
- [ ] Contracts READMEs
- [ ] Leftover search and link check

## Verification

- `grep -rn -i "real-time SSE stream" docs README.md` returns only the reversal note itself.
- Every link in the changed documents resolves; every command in `docs/realtime.md` was run or is copied from a script
  that exists.
- `docs/realtime.md` stays under the configured 800-line limit.

## Risks

- Documenting intended behaviour instead of implemented behaviour. Mitigation: write after phase 13 and cite evidence.
- The Attribution "stream in real time" clause has another meaning; do not edit it by pattern replacement.

## Security

No secrets or real hostnames beyond the existing local examples.

## Next

Plan complete: run `/ak-cook` per phase, then a review and ship.
