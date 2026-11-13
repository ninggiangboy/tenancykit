# Messaging between services

Every cross-service message travels over RabbitMQ. A message is either an **event** (a fact a producer reports) or
a **command** (a request one app sends to one owner); the transport is the same.

## Envelope

There is **one** canonical envelope, in camelCase. The AMQP body is UTF-8 JSON and is exactly the document a tenant
system receives as a webhook, so a consumer can forward a producer's bytes without re-serialising them and the two
deliveries cannot drift apart:

```json
{
  "id": "0198c1a0-7b1e-7c3e-9b52-0d6f6f1c2a10",
  "type": "invoice.created",
  "aggregate": "invoice/42",
  "tenantId": "0198c1a0-0000-7000-8000-000000000042",
  "occurredAt": "2026-11-12T07:00:00.123456Z",
  "schemaVersion": 2,
  "data": { }
}
```

`data` is validated by `contracts/events/<producer>/<type>.schema.json` for an event, or
`contracts/commands/<type>.schema.json` for a command. `id` is a UUID v7 chosen by the producer and is the
deduplication key; `aggregate` is the aggregate the event belongs to, such as `invoice/42`.

**Compatibility rule.** `schemaVersion` is bumped only when the envelope itself changes incompatibly. A reader
rejects a document whose `schemaVersion` it does not know, so a service that understands version *N* never silently
misreads version *N+1*; a breaking change is a coordinated release (D1/D9) in which every producer, consumer and
contract moves to the new version together, with no dual-format window.

AMQP properties: `message_id` = `id`, `type` = `type`, `content_type` = `application/json`, header `tenant-id` =
`tenantId`, delivery mode **persistent**. A message with no schema is never published.

## Replicated data

Platform data that other services need is replicated with **state-transfer events**: one `<aggregate>.changed`
event per aggregate carrying the full current state and a monotonic `version` (the aggregate's optimistic-lock
column). A consumer upserts and drops any event whose `version` is not newer than the row it already holds, so
neither arrival order nor redelivery matters. The snapshot API answers the same JSON plus `version`, and a
replica can therefore be rebuilt from zero by paging the snapshot and then applying live events.

The shared libraries that do all of this are `core/core-replica` (`ReplicaWriter`, `ReplicaEventHandler`),
`core/core-platform-sync` (`SnapshotClient`, `ReplicaBootstrap`) and `core/core-suite` (`SuiteHostResolver`,
`SuiteActorResolver`, `SuiteJwtDecoders`, `SuiteEntitlementFilter`). Every app that consumes `platform.events` keeps
these five tables, which `core-replica/src/main/resources/dev/ngb/core/replica/replica-tables.sql` spells out for an
app that starts fresh:

| Stream | Table | Conflict key |
|---|---|---|
| `tenant.changed` | `tenants` | `id` |
| `tenant-domain.changed` | `tenant_domains` | `id` |
| `tenant-system.changed` | `tenant_systems` | `id` |
| `user-access.changed` | `user_grants` | `user_id` |
| `entitlement.changed` | `app_entitlements` | `(tenant_id, app_code)` |

The rebuild order is `tenants`, `tenant-domains`, `tenant-systems`, `user-access`, `entitlements`, because the
later tables reference `tenants`. The listener container starts only after the snapshot load finishes
(`autoStartup=false`), so messages that arrive meanwhile wait in the durable queue and are applied afterwards; the
version guard drops any the snapshot already covered. Platform never hard-deletes these rows, so a rebuild is
"upsert everything", never "truncate then load".

## Topology

| Exchange (topic, durable) | Producer | Routing key | Queues (one per consuming app) |
|---|---|---|---|
| `platform.events` | Platform | event type, e.g. `tenant.changed` | `billing.platform-events` |
| `billing.events` | Billing | event type | none yet; an app that wants Billing's public events declares its own queue |

Each queue is durable, bound to its exchange by the event types the consumer accepts, and declares
`x-dead-letter-exchange: suite.dlx`. The matching `<queue>.dlq` is bound to `suite.dlx` by the queue name. Consumers
declare their queues and bindings, producers declare their exchange; every declaration is idempotent, so startup
order does not matter.

## Delivery guarantees

- **At least once.** A producer writes the event to its transactional outbox in the same transaction as the
  business change, and a relay publishes it after the commit and marks it only after the broker confirms. A crash
  between confirm and mark republishes.
- **Deduplicated by the consumer.** Every consumer records `id` in its `event_inbox` inside the transaction that
  carries the effect, so a repeated delivery is a no-op.
- **Not ordered.** RabbitMQ does not preserve order across redeliveries; replicas rely on `version`, never on
  arrival order.
- **Poison messages leave the queue.** A consumer retries three times with exponential backoff and then rejects
  without requeue, so the message lands in `<queue>.dlq`.

## Dead-letter runbook

1. Open the management UI (`http://localhost:15672` locally) and inspect the queue `<queue>.dlq`: the payload,
   the `x-death` headers and the consumer stack trace in the log identify the cause.
2. Fix the consumer or the data.
3. Move the messages back with a shovel defined in the management UI: source `<queue>.dlq`, destination
   `<queue>.exchange` with the original routing key (or the queue's exchange). A shovel is one-shot; delete it
   afterwards so a later failure is not silently replayed.
4. If a message is unprocessable by design, record why and drop it; an event is a fact, not a task, and the state
   can always be rebuilt from the producer's snapshot.
