---
title: "Suite realtime signals"
description: "A new infra/realtime service pushes small signals from every app to signed-in console users over SSE, with a shared producer adapter, short replay, burst coalescing and the shell bell."
status: pending
priority: P1
effort: 27d
branch: refactor/group-services-by-kind
tags: [feature, backend, frontend, infra, api]
blockedBy: []
blocks: []
created: 2026-11-22
---

# Suite realtime signals

## Overview

Console users (Platform users and Tenant users) learn about changes only by reloading or calling REST. This plan adds a
suite-wide push channel without breaking the suite rule that no app binds to another app's business exchange.

A new deployable `backend/services/infra/realtime/` (root package `dev.ngb.realtime`) works like mailer: every app
sends a command `realtime.push` through its transactional outbox to the exchange `realtime.commands`; realtime owns the
SSE connections, authentication, audience resolution, a short replay window and fan-out between its own replicas. A
signal is a small hint ("something changed, here is the reference"); the browser refetches through REST, so **REST stays
the source of truth** and a lost signal costs latency, never correctness.

The accepted design comes from the advisory session of 2026-11-22. Its outcome, constraints, non-goals and acceptance
criteria are restated below and are the baseline for every phase.

## Outcome, constraints, non-goals

**Outcome.** A signed-in console user sees a change in the suite within about two seconds without reloading: the bell
badge moves, and the billing and platform remotes refresh the lists the change affects.

**Constraints**

- The suite rule stands: realtime consumes only `realtime.commands` and `platform.events` (as a foreign exchange); it
  never binds to another app's `*.events`. Every service keeps its own broker user and its own database.
- No Redis and no second broker. Fan-out uses a RabbitMQ exchange owned by realtime; replay is a short-lived Postgres
  table with row-level security.
- Signals are small and non-sensitive (type, app, reference, a few scalar fields, at most 1 KiB). The client only
  invalidates; it never patches a cache from a payload.
- The producer declares the permission a recipient needs. Realtime resolves it against its Platform `user-access`
  replica. Realtime adds no permission code and no entitlement of its own.
- Replay window 5 minutes and at most 100 signals per user; client coalescing window 300 ms; server burst threshold 20
  signals per second per user. These are working defaults held in configuration (assumptions, adjustable).
- 1,000 to 5,000 concurrent connections. Java 25 (virtual threads available), Spring MVC.

**Non-goals.** Tenant customers (no customer authentication, no Booking waiting room), WebSocket or any client-to-server
channel, guaranteed delivery beyond the replay window, sensitive content in signals, SMS/push/Slack, email (still
mailer), Redis, a new broker.

## Key decisions

| Decision | Choice | Why (verified in) |
|---|---|---|
| Where the event becomes a signal | A decorator over the app's `EventPublisher` writes a second outbox row of type `realtime.push` in the same transaction | The in-process relay hands every pending row to every listener (`OutboxRelay.claim` filters nothing, `WebhookEventListener.on` takes any `EventEnvelope`), so the row is written with `status = DELIVERED` and only the broker cursor stays pending |
| How the row leaves the app | `BrokerRelay` with the export `realtime.push -> realtime.commands` | `ExportRoutes` already resolves one exchange per type, so billing, survey and notification add one map entry; booking, customer, loyalty and workflow gain a relay |
| Who maps event to permission | The producer (`SignalMappings`), not realtime | `contracts/permissions/*.yaml` already owns the codes; a table in realtime would drift |
| Replay storage | One row per signal, filtered by audience when read | One write per signal instead of one per recipient |
| Fan-out between replicas | Realtime's own outbox row of type `realtime.signal` exported to `realtime.fanout`; each api replica binds an exclusive auto-delete queue | Reuses outbox, `BrokerRelay` and the envelope; at least once, deduplicated by id |
| Connection auth | `fetch` streaming with the `Authorization` header, never `EventSource` with a token in the URL | The Caddy access log would record the URL |
| Permission or tenant change while connected | Worker emits a `realtime.control` message through the same fan-out; the api replica closes the user's connections | The replica client then reconnects with fresh claims and resyncs |
| Burst handling | Server coalesces by `(signal, ref.id)` and sends one `resync {scopes}` above the threshold or when the replay backlog is above 100; the client batches 300 ms and invalidates each visible query key once | REST is the truth, so skipping detail is always safe |

## Extension trace

Next credible variant: Tenant customers (Booking waiting room, still polling today). Stable: the `realtime.push` schema,
`audience` as a tagged union, fan-out, replay and the client core. Changes: a new `audience.kind`, a public token minted
by the Tenant System, and PII retention rules. The plan therefore keeps `audience` a tagged union (cheap) and builds no
plugin point or registry for the customer case; this is speculation, not a requirement.

## Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | [Baseline and preconditions](./phase-01-baseline-and-preconditions.md) | Pending |
| 2 | [Contracts and producer SDK](./phase-02-contracts-and-producer-sdk.md) | Pending |
| 3 | [Realtime service skeleton](./phase-03-realtime-service-skeleton.md) | Pending |
| 4 | [Signal ingest and fan-out](./phase-04-signal-ingest-and-fanout.md) | Pending |
| 5 | [SSE stream API](./phase-05-sse-stream-api.md) | Pending |
| 6 | [Ops and Platform wiring](./phase-06-ops-and-platform-wiring.md) | Pending |
| 7 | [Notification producer](./phase-07-notification-producer.md) | Pending |
| 8 | [Producers: Billing and Survey](./phase-08-producers-billing-survey.md) | Pending |
| 9 | [Producers: Booking, Customer, Loyalty, Workflow](./phase-09-producers-booking-customer-loyalty-workflow.md) | Pending |
| 10 | [Coverage gate and no-signal registry](./phase-10-coverage-gate-and-no-signal-registry.md) | Pending |
| 11 | [Frontend realtime client](./phase-11-frontend-realtime-client.md) | Pending |
| 12 | [Shell bell and remote refresh](./phase-12-shell-bell-and-remote-refresh.md) | Pending |
| 13 | [Smoke, isolation and load test](./phase-13-smoke-isolation-loadtest.md) | Pending |
| 14 | [Docs and scope reversal](./phase-14-docs-and-scope-reversal.md) | Pending |

## Dependencies and ordering

- Phase 1 gates everything: the working tree must be on a committed base, because the new service is created beside
  services that the current branch is still moving.
- Phase 2 (wire contract and producer SDK) gates phases 4, 7, 8, 9, 10 and 11.
- Phases 3, 4, 5 are sequential inside the service. Phase 6 can start once phase 3 exists.
- Phases 7, 8, 9 are independent of each other (each owns one app's directory) and may run in parallel after phases 2
  and 6; each is merged on its own so it can be reverted alone.
- Phase 10 needs 7, 8 and 9. Phase 11 needs only the wire format from phase 2 and can run beside the backend phases.
- Phase 12 needs 5 and 11 (and 7 for the bell). Phase 13 needs everything before it. Phase 14 follows the shape that
  phase 13 proves.

## Risks (top)

| Risk | Mitigation |
|---|---|
| In-process relay delivers `realtime.push` rows to webhook listeners | Broker-only rows are written `DELIVERED`; phase 2 adds a test that no listener sees one |
| Housekeeping deletes `DELIVERED` rows before the broker published them | Each producer's retention query must also require the broker cursor to be finished; phases 2 and 7-9 test it |
| A producer without a broker path fails at startup or silently never publishes | `OutboxRoutes` startup assertion pattern already exists; each producer phase reuses it |
| Command `tenantId` is asserted by the publisher, not authenticated | Documented trust boundary; realtime only delivers to connections of that tenant, and `ops/smoke/isolation.sh` proves the grant is limited to `realtime.commands` |
| 5,000 long connections on Spring MVC | Virtual threads, a bounded per-connection queue, heartbeats and a load test before sign-off |

## Success criteria

- [ ] `docker compose up -d --build --wait` starts `realtime-*` healthy with no Redis.
- [ ] `ops/smoke/realtime-smoke.sh` passes: signal within 2 s, `Last-Event-ID` replay, no delivery to a user without the
      permission or to another tenant, REST resync after the replay window.
- [ ] `ops/smoke/isolation.sh` proves every producer may publish only to `realtime.commands` and realtime reads only its
      own queues.
- [ ] A contract test fails for any `contracts/events/*` event with no signal mapping and no explicit no-signal entry.
- [ ] A burst of 1,000 signals in one second gives few server events (including `resync`) and at most one refetch per
      visible query key and one toast on the client.
- [ ] Load test holds at least 1,000 connections (target 5,000) with p95 delivery below 1 second.
- [ ] Docs carry the dated reversal and no text still calls realtime a non-goal.

## Decisions taken at validation (2026-11-22)

- Billing: only `usage.rated` gets no signal (metering firehose, reason recorded in the registry); `charge.*`,
  `reservation.*` and `wallet.*` map by wallet reference. Re-measure in phase 13 and adjust.
- Workflow `task.created` goes to every holder of `workflow:task:read`; an assignee-only signal waits for an assignee
  field in the event.
- Delivery by pull requests grouped as: contracts plus `core-realtime`; the service plus ops; one PR for each producer app
  (Notification, Billing, Survey, Customer, Loyalty, Booking, Workflow) so each can be reverted alone; frontend; smoke,
  load test and docs.

## Unresolved questions

1. Does Platform's own console need signals (tenant or access changes) beyond the replica streams? Assumed no: the five
   `platform.events` streams are state transfer and are registered as explicit no-signal.
2. Mailroom and Attribution have no `contracts/events` directory; assumed there is nothing to mirror. Confirm while
   executing phase 10.
3. Phase 13 may show that Postgres replay writes limit the target of 5,000 connections or a high signal rate; the
   switch condition (more than about 20,000 connections or sustained rates in the thousands per second) is documented in
   the advisory and is not a part of this plan.

<!-- slug: suite-realtime-signals -->
