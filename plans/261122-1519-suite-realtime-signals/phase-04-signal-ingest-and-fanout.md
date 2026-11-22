---
phase: 4
title: "Signal ingest and fan-out"
status: pending
priority: P1
effort: "2d"
dependencies: [2, 3]
---

# Phase 4: Signal ingest and fan-out

## Goal

The worker consumes `realtime.commands`, validates and stores each signal for a short replay window, and publishes it
through a fan-out exchange so every api replica can deliver it to its own connections. It also turns access changes into
control messages.

## Context

- Commands follow `contracts/commands/README.md`; `AmqpInboundDispatcher` binds the tenant scope from the envelope and
  records the event id in `event_inbox`, so a redelivered command is a no-op.
- `SuiteTopology.declarables` builds durable queues with a DLQ; the per-replica queue of phase 5 is ephemeral and is
  declared separately.
- `BrokerRelay` exports by type to one exchange per type (`ExportRoutes`), so realtime exports `realtime.signal` and
  `realtime.control` to `realtime.fanout` from its own outbox.

## Requirements

1. Queue `realtime.commands` (durable) bound to exchange `realtime.commands` with routing key `realtime.push`, plus its
   dead-letter pair via `suite.dlx`.
2. A handler validates `data` against `realtime.push.schema.json` (reject without requeue on a malformed command so it
   lands in the DLQ), then in one transaction inserts a `signals` row and an outbox row of type `realtime.signal`.
3. `signals` table: `id` (the command's UUID v7), `tenant_id`, `signal`, `app`, `audience_kind`, `audience_user_id`,
   `audience_permission`, `ref_type`, `ref_id`, `data` (jsonb), `created_at`; index `(tenant_id, id)`; RLS on
   `tenant_id`.
4. Exchange `realtime.fanout` (topic, owned and declared by realtime); relay exports `realtime.signal` and
   `realtime.control` to it.
5. Control messages: when the replica handler applies a `user-access.changed` or `tenant.changed` event that changes a
   user's permissions, status or the tenant's status, it writes a `realtime.control` outbox row
   (`{tenantId, userId?}`) in the same transaction.
6. Housekeeping job (worker): delete `signals` older than the retention (replay window plus margin) in bounded batches
   per tenant; delete finished outbox rows; keep any row whose broker cursor is unfinished.
7. Metrics: commands accepted, rejected, signal rows written, relay lag.

## Architecture

```
realtime.commands (queue) --> worker handler --tx--> signals row  +  outbox(realtime.signal)
platform.events --> replica handler --tx--> user_grants upsert  +  outbox(realtime.control) when access changed
outbox --BrokerRelay--> realtime.fanout (topic)  --> one exclusive queue per api replica (phase 5)
```

## Files

Create under `backend/services/infra/realtime/`:
- `contexts/signal/` domain, application (`IngestSignal`, `ControlOnAccessChange`), ports, jOOQ adapters
- `apps/app-boot/.../config/CommandsMessagingConfiguration.java` (queue, bindings, exports, `OutboxRoutes` assertion)
- `apps/app-boot/.../config/OutboxConfiguration.java` (appender and `BrokerRelay`, worker-only job)
- `apps/app-boot/.../jobs/OutboxRelayJob.java`, `SignalHousekeepingJob.java`, `TenantSweep` reuse
- `V2__signals.sql`

Modify:
- `PlatformEventsHandler` or its sink to emit the control row on a relevant transition (shape follows
  `Transition` and `TransitionSink` in notification).

## Steps

1. Migration `V2__signals.sql` with RLS and the index; jOOQ schema regenerated.
2. Domain types: `Signal`, `Audience` (tagged union), validation mirroring the JSON schema; unit tests for each rule.
3. Ingest use case and the AMQP handler; test: valid command writes one `signals` row and one `realtime.signal` outbox
   row; a repeated envelope id writes nothing; a malformed command is rejected without requeue and reaches the DLQ.
4. Configure `MessagingProperties` exports for `realtime.signal` and `realtime.control`; startup assertion that the
   route set is non-empty.
5. Control emission: compare previous and current grant rows (read before the upsert, in the same transaction, as the
   notification replica does) and emit only when the permission set, user status or tenant status changed.
6. Housekeeping with per-tenant sweep and bounded batches; test that an unpublished row survives and a published,
   expired one goes.
7. Integration test with Testcontainers Postgres and RabbitMQ: publish a command, observe the fan-out message on a test
   queue bound to `realtime.fanout`.

## Todo

- [ ] Signals migration and RLS
- [ ] Domain validation and ingest use case
- [ ] Commands queue, DLQ, exports and relay
- [ ] Control emission on access and tenant changes
- [ ] Housekeeping with broker-cursor safety
- [ ] Integration test: command in, fan-out message out

## Verification

- `./gradlew build` in the service passes.
- Integration test proves: command → one signal row and one fan-out message; duplicate command → no second message;
  malformed command → DLQ; a no-op replica upsert → no control message; a permission change → one control message.

## Risks

- Publishing the fan-out message after commit through the relay adds the relay poll interval to latency. Keep the poll
  interval small (hundreds of milliseconds) and measure in phase 13.
- Fan-out lost on a broker outage while connections stay up: the outbox retries (at least once); clients still resync via
  REST on reconnect.

## Security

- The handler never trusts `audience.userId` to cross tenants: delivery in phase 5 is keyed by tenant first.
- The broker user may write only `realtime.fanout`, its own queues and the DLQ; it reads `realtime.commands`,
  `platform.events` and its own queues (phase 6 encodes this).

## Next

Phase 5.
