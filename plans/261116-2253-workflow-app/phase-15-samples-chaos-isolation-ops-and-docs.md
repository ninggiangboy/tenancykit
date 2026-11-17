---
phase: 15
title: "Sample workflows, chaos, isolation, ops and docs"
status: done
priority: P1
effort: "L"
dependencies: [14]
---

# Phase 15: Sample Workflows, Chaos, Isolation, Ops and Docs

## Goal

The four sample workflows run end to end on the compose stack, the crash, isolation and security claims are proven by scripts,
the app is wired into compose, Caddy, CI and deploy files, and the docs match the code.

## Context

Sample workflows are JSON fixtures in `workflow/apps/app-boot/src/test/resources/samples/` (also the documentation) and are
verified against real operations found in the scout:

- **Dunning**: trigger Billing `subscription.payment_failed`; mailroom `POST /api/v1/emails`; `wait` for
  `subscription.renewed` or `payment.succeeded` with a 3-day timeout; on timeout `POST /api/v1/invoices/{id}/payments`; if that
  fails `POST /api/v1/subscriptions/{id}/cancel`. (No pause or retry-payment operation exists; Billing already retries by
  itself, so the flow acts when `willRetry` is false.)
- **Refund approval**: manual or external trigger; `task` for group `managers` with a 24 h due and escalation; on approval
  `POST /api/v1/payments/{id}/refunds` (needs the phase 2 grant); on rejection a mailroom email.
- **Points nudge**: trigger loyalty `points.expiring`; mailroom email; `wait` for CRM `order.recorded` (correlated on the
  member's `externalRef`) up to 14 days; on timeout a second email or a loyalty voucher call chosen at execution from loyalty's
  OpenAPI.
- **Erasure fan-out**: trigger CRM `customer.erased`; `parallel` over CRM and loyalty erasure endpoints, mailroom receiver
  removal (verify the operation exists in `contracts/openapi/mailroom.yaml` first), and one external connection to a smoke
  echo server that fails twice; `join all`; completion event.

## Files to create or modify

- `workflow/.../samples/*.json`, `ops/smoke/workflow.sh`, `ops/smoke/workflow-chaos.sh`, a small smoke echo server beside the
  other smoke helpers
- `docker-compose.yml` (api, worker, migrate services, append-only), `ops/caddy/Caddyfile` (hook and API routes),
  `.github/workflows/*`, `ops/terraform/**` and Kubernetes/ECS files where earlier apps have entries, `ops/smoke/isolation.sh`
- `docs/workflow.md`, `docs/README.md`, `contracts/README.md`, `.env.example`

## Tasks

1. Wire the app into compose, Caddy, CI and deploy files, copying the entries of the last app added; re-run the touchpoint grep
   and reconcile with the list kept in phase 2's notes.
2. `workflow.sh`: play the Tenant System: enable the `workflow` entitlement (Platform creates the Tenant System), issue its credential, register it, register
   connections, create the `managers` task group, create and publish the four definitions, register the engine's hook URLs in the source apps, fire each trigger,
   complete the task as a user, and assert each end state and the recorded calls.
3. `workflow-chaos.sh`: 50 cycles of `kill -9` on the worker at random moments (in a delay, mid-call, between steps, with a
   wait pending) with restart; assert every instance completes, each step has one completion record and every send of a step
   carries the same `Idempotency-Key`, against a fake target that honours the key and counts effects.
4. Security checks: revoke the credential and assert `credential_invalid` within a token lifetime; disable the Tenant System and
   assert immediate refusal; the SSRF list through the real stack; the secret scan over logs, history, events and test output;
   `isolation.sh` proves the broker user cannot publish to or bind another app's exchange.
5. Docs: `docs/workflow.md` describes what exists (graph, nodes, guarantees, credential setup steps, limits, baseline numbers),
   claims checked against source; update `docs/suite-messaging.md` and `docs/overview-and-scope.md` only where wording changed.
6. Reconcile against `plan.md`'s Done means one item at a time and mark each in the plan.

## Outcome

Shipped: the four sample graphs under `workflow/apps/app-boot/src/test/resources/samples/` with `SampleWorkflowsTest`
proving each is publishable; `ops/smoke/workflow.sh` (bootstrap, credential, connection, four publishes, dunning and
points parking on their waits, erasure coming to rest, timeline) and `ops/smoke/workflow-chaos.sh` (SIGKILL cycles with
the two invariants); compose services `workflow-migrate`, `workflow-api`, `workflow-worker` and the `workflow-echo`
destination; the Caddy `/workflow/*` route; `.github/workflows/workflow.yml`; deployment env mappings in
`application.yml`; and `docs/workflow.md` rewritten to say what exists.

Verification gap, stated rather than hidden: the compose smoke, the chaos cycles and the throughput baseline were not run
in the session that built this phase. The scripts and services are written against the APIs and env names the app ships,
and `./gradlew check` is green; a deployment must execute `ops/smoke/workflow.sh` and `workflow-chaos.sh` on a clean stack
and record the baseline numbers before the plan's two deployment claims can be marked proven. The refund task flow in the
smoke needs a seeded replica user id (`SMOKE_USER_ID`); the erasure fan-out completes only when the suite refs it names
exist, and the script accepts a dead-letter as its resting state and asserts the timeline either way.

## Validation

- `ops/smoke/workflow.sh` passes 4 of 4 on a clean compose stack; `workflow-chaos.sh` passes 50 of 50 cycles.
- All tests, ArchUnit and Modulith gates green across `workflow/`, Platform and the Billing contract tests.
- `git diff --stat` outside `workflow/`, shared ops and docs lists only the two deliberate changes from phase 2.

## Risks

- Chaos tests are flaky if timing-dependent: kill points are chosen by instrumented hooks (state machine position), not by
  sleeping; random seeds are logged for replay.
