# Suite messaging

Services meet three ways: signed tokens, RabbitMQ and the snapshot API. This document owns the second and third —
how a fact crosses from one service to another without either one reaching into the other's code or database. The
executable owner of the wire format is [contracts/messaging/README.md](../contracts/messaging/README.md); this
document is the rationale and the operating procedure around it.

## Topology

One topic exchange per producer, one durable queue per consuming app, one dead-letter exchange for the suite:

| Exchange (topic, durable) | Producer | Routing key | Queues |
|---|---|---|---|
| `platform.events` | Platform | event type, e.g. `tenant.changed` | `billing.platform-events`, `notification.platform-events`, `mailroom.platform-events`, `survey.platform-events` |
| `billing.events` | Billing | event type | consumed by its own app only |
| `survey.events` | Survey | event type | no consumer; no other app may bind to it |
| `mailer.commands` | any producer | command type, today `email.send` | `mailer.commands`, `mailer.commands.dlq` |
| `mailer.events` | Mailer | event type, today `email-delivery.changed` | `mailroom.mailer-events`, `mailroom.mailer-events.dlq` |
| `suite.dlx` | — | queue name | `<queue>.dlq` per consumer |

`notification.platform-events`, `mailroom.platform-events` and `survey.platform-events` are durable, bound to
`platform.events` with the five state-transfer routing keys (`tenant.changed`, `tenant-domain.changed`,
`tenant-system.changed`, `user-access.changed`, `entitlement.changed`), declare `x-dead-letter-exchange: suite.dlx`,
and their dead-letter queues are `notification.platform-events.dlq`, `mailroom.platform-events.dlq` and
`survey.platform-events.dlq`.

Notification declares **no exchange of its own**: it publishes the `email.send` command to `mailer.commands` and owns
no event that another app consumes. Mailroom declares no exchange either: it publishes `email.send` to
`mailer.commands` and consumes `email-delivery.changed` on `mailroom.mailer-events`, which binds to mailer's own
`mailer.events`. Mailer produces one event and no command; before this change it produced nothing. Survey declares
`survey.events` for its own three facts, which no app consumes yet.

An exchange carries either events or commands. `mailer.commands` carries commands: a request one app sends to the
service that owns the work, described by `contracts/commands/` rather than by the sender. The envelope is the same;
the difference is who owns the type and that a command's `tenantId` is asserted by the publisher rather than
authenticated, which [contracts/commands/README.md](../contracts/commands/README.md) states in full.

A consumer declares its own queue, its bindings and the queue's dead-letter pair; a producer declares its exchange.
Every declaration is idempotent, so startup order does not matter. No app binds a queue to another app's business
exchange: `billing.events` is consumed by Billing itself, which reads its own facts in its invoicing cycle, and
`survey.events` by nobody. A fact a Tenant's own system reacts to leaves the producing app as a signed webhook to that
Tenant System, and the Tenant System is the only party that connects two apps. Every service has its own broker user,
limited to its own exchanges and queues; a service can publish only to its own exchange and read only its own queues,
and `ops/smoke/isolation.sh` proves a cross-exchange publish is refused.

## Envelope

The AMQP body is UTF-8 JSON and is exactly the document a tenant system would receive as a webhook:

```json
{
  "id": "0198c1a0-7b1e-7c3e-9b52-0d6f6f1c2a10",
  "type": "invoice.created",
  "tenant_id": "0198c1a0-0000-7000-8000-000000000042",
  "occurred_at": "2026-11-12T07:00:00.123456Z",
  "schema_version": 1,
  "data": { }
}
```

`id` is a UUID v7 the producer chooses and is the deduplication key. `schema_version` is bumped only for an
incompatible change. AMQP properties: `message_id` = `id`, `type` = `type`, `content_type` = `application/json`,
header `tenant-id` = `tenant_id`, delivery mode persistent. A message with no schema is never published.

## Versioned replicas

Platform data that apps need is replicated with **state-transfer events**: one `<aggregate>.changed` per aggregate,
carrying the full current state and a monotonic `version` (the aggregate's optimistic-lock column). A consumer
upserts, and drops any event whose `version` is not newer than the row it holds, so neither arrival order nor a
redelivery matters. The five streams and their target tables are in [platform-service.md](platform-service.md).

## Rebuild from zero

An app's replica is rebuilt by paging the snapshot API, never by replaying history:

1. `ReplicaBootstrap` checks the replica's completion marker: a rebuild is needed until a full one has finished at
   least once, or when the `tenants` table has lost every row but the platform pseudo-tenant. The marker is a single
   `replica_state` row written only after every stream finished, so a rebuild that dies partway is retried in full on
   the next boot instead of being mistaken for a complete replica. Deleting that row forces a rebuild after a partial
   restore; a rebuild is not detected when only a child table loses its rows while `tenants` stays populated.
2. It pages each stream in order — `tenants`, `tenant-domains`, `tenant-systems`, `user-access`, `entitlements` —
   through `SnapshotClient`, and upserts each row with the same `ReplicaWriter` a live event uses.
3. The `platform.events` listener is created with `autoStartup=false` and started only after the rebuild, so messages
   that arrive meanwhile wait in the durable queue. The version guard drops any the snapshot already covered.

A rebuild is "upsert everything", never "truncate then load", because Platform never hard-deletes these rows and an
app's business tables reference them. A newly created tenant is the first real test of the path: its `tenant.changed`
event must be recordable in the consumer's inbox before any tenant row exists for it, which is why the inbox carries
no foreign key to `tenants` — the inbox records that a delivery was seen, keyed by the event's tenant id, and
row-level security still isolates it.

## Delivery guarantees

- **At least once.** A producer writes the event to its transactional outbox in the same transaction as the business
  change; a relay publishes after the commit and marks the row only after the broker confirms. A crash between
  confirm and mark republishes.
- **Deduplicated by the consumer.** Every consumer records `id` in its `event_inbox` inside the transaction that
  carries the effect, so a repeated delivery is a no-op.
- **Not ordered.** RabbitMQ does not preserve order across redeliveries; replicas rely on `version`, never on arrival
  order.
- **Poison messages leave the queue.** A consumer retries with exponential backoff and then rejects without requeue,
  so the message lands in `<queue>.dlq`.

## Dead-letter handling

1. Inspect `<queue>.dlq` in the management UI (`http://localhost:15672` locally): the payload, the `x-death` headers
   and the consumer stack trace in the log identify the cause.
2. Fix the consumer or the data.
3. Move the messages back with a one-shot shovel from `<queue>.dlq` to the queue's exchange with the original routing
   key; delete the shovel afterwards so a later failure is not silently replayed.
4. If a message is unprocessable by design, record why and drop it. An event is a fact, not a task: the state can
   always be rebuilt from the producer's snapshot.

## Where the code lives

`core-messaging` owns the outbox, the inbox, the broker relay, the envelope codec and `SuiteTopology`;
`core-replica` owns the replica's tables, the writer, the event handler and the reference DDL;
`core-platform-sync` owns the snapshot client, the bootstrap and the connection settings; `core-suite` owns the token
decoder, the host and actor resolvers and the entitlement filter. The snapshot HTTP transport is implemented in each
app's boot layer over `core-feign`, which owns the one codec, retryer, token client and error-mapper base. All are
shared by every service build, so a new app is a wiring exercise rather than a reimplementation, and ArchUnit keeps
each module free of any single service's types.
