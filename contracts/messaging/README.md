# Messaging between services

Every cross-service message travels over RabbitMQ. A message is either an **event** (a fact a producer reports) or
a **command** (a request one app sends to one owner); the transport is the same.

## Envelope

There is **one** canonical envelope, in camelCase, produced by `EventDocument`. The AMQP body is UTF-8 JSON, and the
webhook writer emits the same bytes, so a consumer can forward a producer's output without re-serialising it and the
two deliveries cannot drift apart:

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

`data` is validated by `contracts/events/<producer>/<type>.schema.json`: only an event with a schema is published
and only an event type with a schema may be subscribed to. `id` is a UUID v7 chosen by the producer and is the
deduplication key; `aggregate` is the aggregate the event belongs to, such as `invoice/42`.

**Compatibility rule.** `schemaVersion` is bumped only when the envelope itself changes incompatibly. A reader
rejects a document whose `schemaVersion` it does not know, so a service that understands version *N* never silently
misreads version *N+1*; a breaking change is a coordinated release (D1/D9) in which every producer, consumer and
contract moves to the new version together, with no dual-format window.

AMQP properties: `message_id` = `id`, `type` = `type`, `content_type` = `application/json`, header `tenant-id` =
`tenantId`, delivery mode **persistent**. A message with no schema is never published.

### Commands

A message is an event (a fact a producer reports) or a **command** (a request one app sends to the app that owns
the work). The envelope above is identical for both; only the owner of the type differs. An event type belongs to
the service that emits it and its schema lives under `contracts/events/<app>/`; a command type belongs to the
service that performs it, so its schema lives under `contracts/commands/`, describes `data` only, and a producer
implements the contract rather than editing it. See [commands/README.md](../commands/README.md), including the
trust boundary: a command's `tenantId` is asserted by the publisher, not authenticated by the consumer.

## Replicated data

Platform data that other services need is replicated with **state-transfer events**: one `<aggregate>.changed`
event per aggregate carrying the full current state and a monotonic `version` (the aggregate's optimistic-lock
column). A consumer upserts and drops any event whose `version` is not newer than the row it already holds, so
neither arrival order nor redelivery matters. The snapshot API answers the same JSON plus `version`, and a
replica can therefore be rebuilt from zero by paging the snapshot and then applying live events.

The shared libraries that do all of this are `backend/libs/core/core-replica` (`ReplicaWriter`, `ReplicaEventHandler`),
`backend/libs/core/core-platform-sync` (`SnapshotClient`, `ReplicaBootstrap`) and `backend/libs/core/core-suite` (`SuiteHostResolver`,
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
| `platform.events` | Platform | event type, e.g. `tenant.changed` | one `<app>.platform-events` per replicating app; each service's `MessagingConfiguration` declares its own |
| `billing.events` | Billing | event type | consumed by its own app only |
| `survey.events` | Survey | event type | no consumer; no other app may bind to it |
| `mailer.commands` | any producer | command type, today `email.send` | `mailer.commands` (and its `mailer.commands.dlq`) |
| `mailer.events` | Mailer | event type, today `email-delivery.changed` | `mailroom.mailer-events` (and its `mailroom.mailer-events.dlq`) |

Each `<app>.platform-events` queue binds to `platform.events` with the five state-transfer routing keys, declares
`x-dead-letter-exchange: suite.dlx`, and has a matching `.dlq`. `platform.events` is Platform's, so a consumer binds to
it as a foreign exchange: its broker user may bind to it but not declare it. Notification declares no exchange: it produces `email.send` into `mailer.commands` and exports no event
of its own. Mailroom declares no exchange either: it produces `email.send` and consumes `email-delivery.changed` from
mailer's `mailer.events`, which is the suite's first exchange owned by a service that is otherwise a pure consumer.
Survey declares `survey.events` for its own three facts, which have no consumer, and no other app may bind to it.
Booking declares no exchange at all: its ten facts leave only as signed webhooks, so no app may bind to booking and
booking binds to nothing but `platform.events`.

Each queue is durable, bound to its exchange by the event types the consumer accepts, and declares
`x-dead-letter-exchange: suite.dlx`. The matching `<queue>.dlq` is bound to `suite.dlx` by the queue name. Consumers
declare their queues and bindings, producers declare their exchange; every declaration is idempotent, so startup
order does not matter.

## The suite rule

Suite apps never bind to another app's business exchange. The broker carries Platform's replicated data, the suite's
infrastructure commands and outcomes (`mailer.commands`, `mailer.events`) and an app's traffic with itself — Billing
consumes its own `billing.events` in its invoicing cycle, and `survey.events` has no consumer at all. A fact a Tenant's
own system reacts to leaves the producing app as a signed **webhook** to that Tenant System, and the Tenant System is
the only party that connects two apps. A note on `attribution.ingest`: it is a queue an app binds to its own exchange
for its own worker, not a crossing between apps. A new consumer inside the suite is a change of this rule, not a new
binding, so neither `billing.events` nor `survey.events` is ever bound by another app.

**Workflow is the Tenant side of this rule, not a new consumer.** It acts as a Tenant System: it calls apps by their
public API with a Tenant-owned credential, receives their signed webhooks as triggers, and binds to no app's business
exchange. Its `workflow.platform-events` queue binds to `platform.events` only, for the same five state-transfer keys
every app replicates, and its `workflow.runnable` queue is internal to the app. A fact another app emits reaches
workflow exactly as it reaches any Tenant System: as one signed webhook the Tenant points at workflow's hook URL.

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
