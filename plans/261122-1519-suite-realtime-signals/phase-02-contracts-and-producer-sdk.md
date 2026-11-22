---
phase: 2
title: "Contracts and producer SDK"
status: pending
priority: P1
effort: "2d"
dependencies: [1]
---

# Phase 2: Contracts and producer SDK

## Goal

Define the wire contract once, and give every app a shared way to turn an existing domain event into a `realtime.push`
outbox row in the same transaction, without any in-process listener ever seeing it.

## Context

- `backend/libs/core/core-messaging/.../outbox/JooqOutboxAppender.java` inserts a row with default `status = PENDING`.
- `OutboxRelay.claim` takes every `PENDING` row and hands it to every Spring listener; `WebhookEventListener.on` accepts
  any `EventEnvelope`. A second row of another type would therefore reach the webhook machinery.
- `BrokerRelay` has its own cursor (`published_at`, `publish_status`, `publish_next_at`) and filters by `ExportRoutes`.
- `contracts/commands/README.md` states the command rules: the consumer owns the schema, `tenantId` is asserted by the
  publisher, and a command schema must not live under `contracts/events/`.

## Requirements

1. `contracts/commands/realtime.push.schema.json` describes `data`:
   `signal` (string, `^[a-z][a-z0-9-]*(\.[a-z0-9_-]+)+$`, at most 64), `app` (string, the app code), `audience` (tagged
   union: `{kind:"user", userId:uuid}` or `{kind:"permission", code:string}`), optional `ref` (`{type, id}`), optional
   `data` (flat object of string, number or boolean values, at most 1 KiB serialized), `additionalProperties: false`.
2. A broker-only append path: a signal row is written with `status = DELIVERED` so the in-process relay never claims it,
   while `publish_status` stays `PENDING`.
3. A new core module `core-realtime` (producer SDK) with: the `RealtimeSignal` record and its JSON mapping, a
   `SignalMappings` registry (event type to app, permission or user selector, reference extractor, optional small
   fields), and `RealtimeMirroringEventPublisher`, a decorator of `EventPublisher` that delegates first and then appends
   the signal row when a mapping exists.
3b. A direct API `RealtimeSignals.push(tenant, RealtimeSignal)` in the same module for a producer that has no domain
   event to mirror (Notification uses it); it shares the broker-only append and the validation below.
4. Validation at append time against the schema constraints (size, enumerated shape); an invalid mapping fails the
   transaction loudly, never publishes a malformed command.
5. A coverage helper `SignalCoverage` that an app test calls with its `contracts/events/<app>` directory, its
   `SignalMappings` and its explicit no-signal set, and that fails on any event type in neither.
6. Retention safety: documented and tested rule that a producer's outbox cleanup never deletes a row whose broker cursor
   is unfinished, including broker-only rows.

## Architecture

```
domain event --> EventPublisher (decorated)
                   |-- JooqOutboxAppender.publish(event)           row A: type=invoice.paid, status=PENDING (webhooks)
                   `-- SignalMappings.find(event.type()) present?
                          `-- appendBrokerOnly(realtime.push)      row B: type=realtime.push, status=DELIVERED,
                                                                          publish_status=PENDING
BrokerRelay (exports: realtime.push -> realtime.commands) publishes row B after commit; row A is untouched.
```

Row B gets a fresh UUID v7 id (the dedupe key at the consumer) and the same `tenant_id`, `occurred_at` and aggregate as
the source event.

## Files

Create:
- `contracts/commands/realtime.push.schema.json`
- `backend/libs/core/core-realtime/` (Gradle module, wired in `backend/libs/core/settings.gradle.kts`, with
  `RealtimeSignal`, `Audience`, `SignalMappings`, `SignalMapping`, `RealtimeMirroringEventPublisher`,
  `SignalCoverage`, tests)

Modify:
- `backend/libs/core/core-messaging/.../outbox/JooqOutboxAppender.java` (add `appendBrokerOnly(DomainEvent)`), and its
  schema contract `schema-contract.md` (document the broker-only row)
- `backend/libs/core/core-arch` rules if module boundaries are enforced there
- `contracts/commands/README.md` (add the `realtime.push` row, its exchange and consumer)

## Steps

1. Write the JSON Schema and a set of valid and invalid sample documents under the module's test resources.
2. Add `appendBrokerOnly` with a unit test that inserts a row and asserts `status = DELIVERED`,
   `publish_status = PENDING`, and that a running `OutboxRelay` over a Testcontainers database does not claim it.
3. Build `core-realtime`: records, mapping DSL (`on(eventType).app(...).permission(...)|toUser(...)`, `ref(type,
   payloadPath)`, `field(name, payloadPath)`), the decorator, size and shape validation.
4. Test the decorator: mapped type writes two rows, unmapped type writes one, a mapping that references a missing payload
   path fails the transaction, the signal row carries the source event's tenant.
5. Implement `SignalCoverage` and test it against fixture directories (a covered set passes, a missing type fails with
   the type's name in the message).
6. Add a retention test in `core-messaging` that proves a cleanup query written with the documented predicate keeps a
   broker-only row whose `publish_status` is `PENDING`.
7. Update the READMEs. Keep names free of plan or phase numbers in code, tests and migration names.

## Todo

- [ ] `realtime.push.schema.json` with valid and invalid samples
- [ ] `appendBrokerOnly` and its schema-contract note
- [ ] `core-realtime` module, mapping DSL, decorator, validation
- [ ] `SignalCoverage` helper
- [ ] Tests: relay ignores broker-only rows, decorator behaviour, retention predicate
- [ ] `contracts/commands/README.md` updated

## Verification

- `cd backend/libs/core && ./gradlew build` passes, including the new module and `core-arch`.
- A test proves the in-process `OutboxRelay` does not deliver a `realtime.push` row to a listener.
- A test proves the schema rejects: unknown properties, an `audience` with both kinds, a `data` over 1 KiB, a nested
  value in `data`.

## Risks

- Two rows per event doubles outbox growth for mapped types. Mitigation: signal rows are small and cleaned on the
  broker cursor; measure in phase 13.
- A mapping extractor that reads a renamed payload field breaks at runtime. Mitigation: coverage and mapping tests read
  the real event schemas in `contracts/events/`.

## Security

- The signal carries only reference and non-sensitive scalars; the mapping DSL has no way to copy the whole payload.
- The command's `tenantId` is publisher-asserted (documented trust boundary); nothing here relaxes it.

## Next

Phases 3 and 11 can start; producers (7, 8, 9) need this phase.
