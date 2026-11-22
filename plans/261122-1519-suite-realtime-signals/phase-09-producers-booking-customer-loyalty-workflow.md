---
phase: 9
title: "Producers: Booking, Customer, Loyalty, Workflow"
status: pending
priority: P2
effort: "3d"
dependencies: [2, 6]
---

# Phase 9: Producers: Booking, Customer, Loyalty, Workflow

## Goal

Four apps that today only relay events in process (for signed webhooks) gain a broker relay for exactly one command,
`realtime.push`, and mirror their event catalogs through the shared decorator.

## Context

- Booking, Customer and Loyalty run an in-process `OutboxRelay` and declare no exchange; their broker config is a plain
  `Declarables` for the `platform.events` replica queue (see `booking/.../MessagingConfiguration.java`,
  `OutboxConfiguration.java` which says `BrokerRelay` is deliberately absent). Workflow has an `OutboxConfiguration`
  with its own relay; confirm its shape before editing.
- The broker path is independent: `BrokerRelay` has its own cursor on the same row, so adding it does not disturb the
  in-process relay (`core-messaging` schema contract). Existing migrations already carry the broker columns because the
  core contract requires them; verify per app and add a migration only if a column is missing.
- Event counts: Booking 10, Customer 4, Loyalty 12, Workflow 10 schema files (plus a README).
- Read codes: `booking:booking:read`, `booking:waitlist:read`, `customer:customer:read`, `loyalty:member:read`,
  `loyalty:voucher:read`, `workflow:instance:read`, `workflow:task:read`.

## Requirements

1. Each app builds a `MessagingProperties` used only by `BrokerRelay` (exchange `realtime.commands`, exports
   `realtime.push -> realtime.commands`, relay policy from its own settings). It must **not** call `SuiteTopology` for
   that exchange: realtime owns and declares `realtime.commands`, and a producer's broker user gets `write` only.
2. A worker-only relay job per app (shape of notification's `OutboxRelayJob`, using the app's existing per-tenant
   sweep), plus the `OutboxRoutes` startup assertion.
3. Decorator and `SignalMappings` per app with the same rules as phase 8 (permission, reference, scalars only, no PII).
   Customer and Loyalty events concern personal data: signals carry the member or customer id reference only; erasure
   events (`customer.erased`, `member.erased`) map to a plain list refresh signal with no identifier that survives
   erasure rules.
4. Broker user grants: each of the four gains `write` on `realtime.commands`; its `read`, queue and exchange sets stay as
   today.
5. Retention predicate and coverage test per app as in phase 8.
6. Each app is its own commit and is revertable alone (an app's change touches only its own directory plus its own
   block in `declare-users.sh`).

## Files

Modify per app under `backend/services/apps/<app>/`:
- `apps/app-boot/.../boot/config/MessagingConfiguration.java`, `OutboxConfiguration.java`, a new
  `RealtimeConfiguration.java`
- the app's job configuration and a new `OutboxBrokerRelayJob`
- settings class (relay settings for the broker path if not shared with the in-process relay)
- outbox cleanup job and tests; `SignalCoverageTest`; mapping tests
- `build.gradle.kts` of `apps:app-boot` (dependencies `core-realtime`, AMQP template if absent)

Also modify: `ops/rabbitmq/declare-users.sh` (four blocks).

## Steps (repeat for each app, in this order: Customer, Loyalty, Booking, Workflow)

1. Read the app's messaging and outbox configuration, its worker job wiring and its settings; confirm whether a
   `RabbitTemplate` with publisher confirms is already configured (the replica listener uses a connection factory) and
   whether the broker columns exist in the migrations.
2. Add the relay, the job and the `OutboxRoutes` assertion; add a migration for missing broker columns only if needed
   (name it for what it does, for example `outbox-broker-cursor`).
3. Add the mapping table (event, permission, ref path, scalar fields) in test resources and the mappings in code.
4. Wire the decorator so it is the only `EventPublisher` bean the use cases see.
5. Run the app's existing webhook tests: they must be unchanged, which proves the in-process relay ignores signal rows.
6. Add coverage, mapping, retention and relay-publish tests (a signal row reaches a test queue bound to
   `realtime.commands`).
7. Update the broker grant and commit the app alone.

## Todo

- [ ] Customer: relay, job, mappings, tests
- [ ] Loyalty: relay, job, mappings, tests
- [ ] Booking: relay, job, mappings, tests
- [ ] Workflow: confirm shape, relay, job, mappings, tests
- [ ] Four broker grants
- [ ] Per-app coverage tests green

## Verification

- For each app: `cd backend/services/apps/<app> && ./gradlew build` passes, with the existing webhook delivery tests
  untouched and green.
- A test per app publishes one mapped event and asserts one `realtime.push` message on a queue bound to
  `realtime.commands` and a normal webhook delivery for the same event.

## Risks

- Adding a RabbitMQ publisher to an app that never published makes a broker outage affect the worker's relay job; the
  relay backs off per row and never blocks the in-process path, but confirm with a test that stops the broker.
- The hook-heavy Workflow engine may emit events on hot paths; measure after wiring and apply phase 8's exclusion rule.
- PII leakage through a careless `field(...)` mapping: the allow-list test per app fails the build.

## Security

- The producer broker user can write only `realtime.commands` (no configure on it); phase 13 proves it.
- Erasure semantics are preserved: nothing in a signal or in realtime's replay rows can resurrect an erased subject.

## Next

Phase 10.
