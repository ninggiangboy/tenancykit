---
phase: 5
title: "SSE stream API"
status: pending
priority: P1
effort: "3d"
dependencies: [4]
---

# Phase 5: SSE stream API

## Goal

An authenticated user opens one SSE stream, receives only the signals they may see, can resume with `Last-Event-ID`, and
is protected from bursts by server-side coalescing and a `resync` event.

## Context

- Java 25 with Spring MVC; virtual threads are available for long-lived connections.
- Browsers' `EventSource` cannot send an `Authorization` header; the client (phase 11) uses `fetch` streaming, so the
  server only needs a normal bearer-protected `GET` that returns `text/event-stream`.
- Permissions per user come from the Platform replica (`user_grants`), loaded when a connection opens.

## Requirements

1. `GET /api/v1/stream` (reached as `/realtime/api/v1/stream` through Caddy). Header `Authorization: Bearer`, optional
   `Last-Event-ID`.
2. Events on the wire: `hello` (`{serverTime, resumed}`), `signal` (`id` = signal UUID v7; data = the signal),
   `resync` (`{scopes:["billing", ...]}` or `["*"]`), and a comment heartbeat every 20 seconds (configurable).
3. Audience rule: a connection of `(tenant, user)` receives a signal when tenant matches and the audience is that user,
   or the audience is a permission code the user currently holds.
4. Replay on connect: read `signals` of the tenant with `id > Last-Event-ID` and `created_at` inside the replay window,
   filter by audience, send in order. If the id is older than the window, or the matching backlog exceeds 100, send one
   `resync` instead.
5. Live delivery: each api replica owns an exclusive, auto-delete queue bound to `realtime.fanout` with `#`; a listener
   dispatches `realtime.signal` to the in-memory registry and `realtime.control` to close that user's (or the tenant's)
   connections.
6. Coalescing: each connection has a bounded queue keyed by `(signal, ref.id)` keeping the latest; above 20 signals per
   second for one user, or on queue overflow, drop the queued items and emit one `resync` with the affected `app`s.
7. Limits: at most 5 concurrent connections per user (oldest closed first), and the stream closes at JWT expiry.
8. Metrics: open connections (gauge), signals sent, resyncs, replay size, delivery latency from the command's
   `occurredAt` to the socket write (histogram).

## Architecture

```
Browser --GET /stream (Bearer)--> api replica
   connect: validate JWT -> load permissions (replica) -> registry.add(conn)
            -> replay(Last-Event-ID)  | resync
   live:    fanout queue msg -> registry.dispatch(tenant) -> per-conn coalescing queue -> writer (virtual thread)
   control: fanout msg 'realtime.control' -> registry.close(tenant,user) -> client reconnects, resyncs
```

The writer for each connection runs on a virtual thread and drains its bounded queue; a slow client cannot block the
fan-out listener.

## Files

Create under `backend/services/infra/realtime/`:
- `contexts/signal/.../stream/` `ConnectionRegistry`, `StreamConnection`, `Coalescer`, `AudienceFilter`, `ReplayReader`
- `apps/app-boot/.../web/StreamController.java`, `StreamProperties.java`
- `apps/app-boot/.../config/FanoutConfiguration.java` (api profile: exclusive auto-delete queue, listener)
- Tests: `AudienceFilterTest`, `CoalescerTest`, `ReplayReaderTest`, `StreamIntegrationTest`, `BurstTest`

Modify:
- OpenAPI contract `contracts/openapi/realtime.yaml` (new) describing the stream endpoint and event shapes

## Steps

1. Write `AudienceFilter` and its truth table tests (user audience, permission audience, other tenant, missing
   permission, disabled user).
2. Write `Coalescer` with tests: duplicate keys collapse, order of first appearance preserved, overflow and rate
   threshold produce one `resync` listing the distinct apps, and a drained queue starts clean.
3. `ReplayReader`: window and backlog rules, ordering by id, audience filtering; test each branch including an id from
   before the window and exactly 100 versus 101 matches.
4. `ConnectionRegistry` and `StreamController` with virtual-thread writers, heartbeat, JWT-expiry close and the
   per-user cap; test the cap and expiry using a fake clock.
5. `FanoutConfiguration`: exclusive, auto-delete queue named from the instance id, bound to `realtime.fanout`; dispatch
   and control handling.
6. Integration test: two api instances in one test (two contexts or a registry pair), publish a fan-out message, and
   assert only the instance holding the connection writes it; a control message closes the connection.
7. Burst test: feed 1,000 signals in one second for one user and assert a handful of events including one `resync`.
8. Add the OpenAPI document and the contract test that compares it with the controller (pattern: notification's
   `OpenApiContractTest`).

## Todo

- [ ] Audience filter with tests
- [ ] Coalescer with tests
- [ ] Replay reader with tests
- [ ] Registry, controller, heartbeat, expiry, per-user cap
- [ ] Per-replica fan-out queue and control handling
- [ ] Multi-instance and burst integration tests
- [ ] OpenAPI document and contract test

## Verification

- Service `./gradlew build` passes.
- Multi-instance integration test: a signal reaches exactly the matching connection on the right replica; a user without
  the permission receives nothing; another tenant's user receives nothing.
- Reconnect test: `Last-Event-ID` inside the window replays the missed signals in order; outside it yields one `resync`.

## Risks

- A thread-per-connection model at 5,000 connections: virtual threads and a bounded queue keep memory predictable;
  verified by the load test in phase 13.
- Registry growth when a client disappears without a clean close: heartbeat write failure removes the connection.
- Permission staleness between control messages: bounded by the control path and by JWT expiry closing the stream.

## Security

- JWT validation uses the same decoder as the other services; the principal must be a user, not a machine.
- No signal is delivered across tenants, ever; the tenant key is checked before the audience.
- Replay reads run under the connection's tenant scope so row-level security applies.

## Next

Phase 6 (ops wiring) can already be developed alongside; phase 12 needs this endpoint.
