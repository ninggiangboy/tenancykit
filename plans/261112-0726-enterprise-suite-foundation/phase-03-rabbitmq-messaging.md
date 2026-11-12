---
phase: 3
title: "RabbitMQ messaging"
status: pending
priority: P1
effort: "3d"
dependencies: [1, 2]
---

# Phase 3: RabbitMQ messaging

## Goal

Give `core-messaging` a broker path: a relay that publishes committed outbox rows to RabbitMQ with publisher confirms, and an inbound adapter that dedupes through the inbox and dead-letters poison messages. Billing starts publishing its public events to `billing.events`.

## Key insights

- Today `OutboxRelay` claims a row, dispatches it to in-process Spring listeners, and marks it `DELIVERED` in one transaction. Broker publishing has different failure semantics (a network call that cannot join the database transaction), so it gets **its own cursor on the same row**: `published_at`, `publish_attempts`, `publish_next_at`. The in-process path is untouched.
- At-least-once is guaranteed by "confirm, then mark". A crash between them republishes; every consumer dedupes by event id through `event_inbox`.
- Billing splits runtime into api and worker profiles (`billing/apps/app-boot/src/main/resources/application-worker.yml`). The broker relay and every listener run only in the worker profile.
- The relay runs once per tenant because `outbox` has RLS. It plugs into the existing `TenantSweep` exactly like the in-process relay.

## Files

Create in `core/core-messaging/src/main/java/dev/ngb/core/messaging/`:
- `broker/BrokerRelay.java`: claims rows with `published_at is null and publish_next_at <= now()` and a type in the exported set (`for update skip locked`), publishes the envelope, waits for confirms per batch, and marks `published_at`. On failure it backs off with core `Backoff`; after the attempt limit it sets `publish_status = 'DEAD'`.
- `broker/ExportRoutes.java`: maps an event type to an exchange (`invoice.*` → `billing.events`, `notification.requested` → `notification.requests`). It is built from configuration, and types not listed are not exported.
- `broker/EnvelopeCodec.java`: `EventEnvelope` ↔ JSON body (the format in `contracts/messaging/README.md`). The body bytes are produced once and never re-serialized downstream.
- `inbound/InboundEventHandler.java` (interface `String consumer(); boolean accepts(String type); void handle(EventEnvelope event)`).
- `inbound/AmqpInboundDispatcher.java`: a listener that decodes the body, binds `RequestScope.tenant(tenantId)` (or platform scope for the platform pseudo-tenant), opens `UnitOfWork`, and calls `EventInbox.firstTime` then the matching handlers. It retries 3 times with exponential backoff (Spring Retry stateless interceptor), then rejects without requeue, so the message lands in the DLQ.
- `topology/SuiteTopology.java`: builds `Declarables` from configuration (exchange for producers; queue, bindings, DLX and DLQ for consumers).
- `config/MessagingProperties.java`: `suite.messaging.exchange`, `exports`, `queues[].name/exchange/routing-keys`, `relay.batch-size/max-attempts/backoff`.

Modify:
- `core/core-messaging/build.gradle.kts`: add `org.springframework.amqp:spring-rabbit`, `org.springframework.retry:spring-retry`; test `org.testcontainers:rabbitmq`, `org.testcontainers:postgresql`.
- `core/core-messaging/src/main/resources/dev/ngb/core/messaging/schema-contract.md`: add the three publish columns.
- `billing/apps/app-boot/src/main/resources/db/migration/V22__outbox_broker_cursor.sql`: `alter table outbox add column published_at timestamptz, add column publish_status text not null default 'PENDING' check (publish_status in ('PENDING','PUBLISHED','DEAD','SKIPPED')), add column publish_attempts integer not null default 0, add column publish_next_at timestamptz not null default now();` plus a partial index on pending rows. Existing rows become `SKIPPED` so history is not replayed.
- `billing/libs/jooq-schema`: regenerate (`-Djooq.update=true`).
- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/jobs/OutboxJobs.java`: also run `BrokerRelay` per tenant in the worker profile.
- `billing/apps/app-boot/src/main/resources/application.yml` and `application-worker.yml`: `spring.rabbitmq.*` from env (`BILLING_RABBITMQ_URL` or host/port/user/password), `publisher-confirm-type: correlated`, `suite.messaging.exchange: billing.events`, exports of every type under `contracts/events/billing/`.
- `docker-compose.yml`: service `rabbitmq` (`rabbitmq:4.1-management`, ports 5672 and 15672, credentials from `.env`, a volume).
- `.env.example`: RabbitMQ variables.
- Billing's test base configuration: a shared `RabbitMQContainer` next to the PostgreSQL container.

## Implementation steps

1. Add the dependencies and the `MessagingProperties`, `SuiteTopology` and `EnvelopeCodec` classes; unit-test the codec against a schema sample.
2. Write `BrokerRelay` and its integration test (Postgres + RabbitMQ containers): a committed row arrives once; with the broker stopped the row stays pending and is published after restart; a type outside the exports is never published.
3. Write `AmqpInboundDispatcher` and its tests: a duplicate message runs the handler once; a handler that always throws sends the message to `<queue>.dlq` after 3 attempts; a message for an unknown type is acked and ignored.
4. Billing: migration, jOOQ regeneration, job wiring, configuration, compose service.
5. Billing integration test: issuing an invoice publishes `invoice.created` to `billing.events` (a test queue bound with `#` receives it with the envelope from the contract).
6. Document the dead-letter runbook in `contracts/messaging/README.md`: how to inspect the DLQ in the management UI and how to move messages back with a shovel.

## Todo

- [x] Broker cursor columns in the core contract and in Billing's migration
- [x] `BrokerRelay` with confirms, backoff and DEAD state
- [x] `AmqpInboundDispatcher` with inbox, retry and DLQ
- [x] Topology from configuration
- [x] Billing publishes its public events; compose runs RabbitMQ
- [x] Integration tests for publish, outage, dedupe and DLQ

## Verification

- `cd core && ./gradlew :core-messaging:test` green.
- `cd billing && ./gradlew build` green, including the new publishing test.
- Locally: `docker compose up -d rabbitmq`, run Billing in the worker profile, issue an invoice, and see one message in a test queue in the management UI at `http://localhost:15672`.

## Risks

- Lost messages if the relay marks rows before the confirm. Mitigation: mark only after `waitForConfirmsOrDie`; the test kills the broker mid-batch.
- Unbounded republish of a poison row. Mitigation: `publish_status = 'DEAD'` after the limit, and a metric `<prefix>.outbox.publish.dead`.
- Ordering: RabbitMQ does not order across redeliveries. Replicas rely on `version`, never on arrival order.

## Security

Broker credentials come from env, never from committed files. Each service gets its own RabbitMQ user in phase 13; until then the dev default user is acceptable only in compose.
