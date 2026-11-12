# Messaging between services

Every cross-service message travels over RabbitMQ. A message is either an **event** (a fact a producer reports) or
a **command** (a request one app sends to one owner); the transport is the same.

## Envelope

The AMQP body is UTF-8 JSON and is exactly the document a tenant system would receive as a webhook, so the
Notification Hub can forward a producer's bytes without re-serialising them:

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

`data` is validated by `contracts/events/<producer>/<type>.schema.json` for an event, or
`contracts/commands/<type>.schema.json` for a command. `id` is a UUID v7 chosen by the producer and is the
deduplication key; `schema_version` is bumped only when the schema changes incompatibly.

AMQP properties: `message_id` = `id`, `type` = `type`, `content_type` = `application/json`, header `tenant-id` =
`tenant_id`, delivery mode **persistent**. A message with no schema is never published.

## Replicated data

Platform data that other services need is replicated with **state-transfer events**: one `<aggregate>.changed`
event per aggregate carrying the full current state and a monotonic `version` (the aggregate's optimistic-lock
column). A consumer upserts and drops any event whose `version` is not newer than the row it already holds, so
neither arrival order nor redelivery matters. The snapshot API answers the same JSON plus `version`, and a
replica can therefore be rebuilt from zero by paging the snapshot and then applying live events.

## Topology

| Exchange (topic, durable) | Producer | Routing key | Queues (one per consuming app) |
|---|---|---|---|
| `platform.events` | Platform | event type, e.g. `tenant.changed` | `billing.platform-events`, `notification.platform-events` |
| `billing.events` | Billing | event type | `notification.billing-events` |
| `notification.requests` | any app | `notification.requested` | `notification.requests` |

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
