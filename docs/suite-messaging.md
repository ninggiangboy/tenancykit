# Suite messaging

Services meet three ways: signed tokens, RabbitMQ and the snapshot API. This document owns the second and third —
how a fact crosses from one service to another without either one reaching into the other's code or database. The
executable owner of the wire format is [contracts/messaging/README.md](../contracts/messaging/README.md); this
document is the rationale and the operating procedure around it.

## Topology

One topic exchange per producer, one durable queue per consuming app, one dead-letter exchange for the suite:

| Exchange (topic, durable) | Producer | Routing key | Queues |
|---|---|---|---|
| `platform.events` | Platform | event type, e.g. `tenant.changed` | `billing.platform-events` |
| `billing.events` | Billing | event type | none yet |
| `suite.dlx` | — | queue name | `<queue>.dlq` per consumer |

A consumer declares its own queue, its bindings and the queue's dead-letter pair; a producer declares its exchange.
Every declaration is idempotent, so startup order does not matter. Billing's `billing.events` has no consumer today;
it exists so a future app can react to a Billing fact without Billing changing. Every service has its own broker user,
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

1. `ReplicaBootstrap` checks whether the replica holds any tenant other than the platform pseudo-tenant. If it does
   not, it rebuilds.
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
`core-replica` owns the snapshot client, the bootstrap, the writer, the event handler, the host and actor resolvers
and the entitlement filter. Both are shared by every service build, so a new app is a wiring exercise rather than a
reimplementation, and ArchUnit keeps each module free of any single service's types.
