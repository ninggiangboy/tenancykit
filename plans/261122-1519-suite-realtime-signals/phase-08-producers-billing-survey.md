---
phase: 8
title: "Producers: Billing and Survey"
status: pending
priority: P2
effort: "2d"
dependencies: [2, 6]
---

# Phase 8: Producers: Billing and Survey

## Goal

Billing and Survey mirror their existing events into signals using the shared decorator, add the `realtime.push` export
to their existing broker relay, and prove every event type is either mapped or explicitly excluded.

## Context

- Both already run a `BrokerRelay` and own an exchange (`billing.events`, `survey.events`); `exports` is a map from type
  to exchange, so the new entry coexists with the existing ones.
- Billing's event types come from `SchemaEventTypeCatalog` (21 schemas under `contracts/events/billing/`); Survey has
  three (`survey-version.published`, `survey.closed`, `survey-response.submitted`).
- Permission codes live in `contracts/permissions/billing.yaml` and `survey.yaml`.
- Billing carries machine-driven traffic (`usage.rated`, `charge.*`, `reservation.*`, `wallet.*`) that can be high rate.

## Requirements

1. Each app wires `RealtimeMirroringEventPublisher` around its `EventPublisher` bean and a `SignalMappings` bean.
2. Mapping rule per event: signal name = the event type; `app` = the app code; `permission` = the read code of the
   screen the event affects (for example an invoice event maps to the invoice read permission); `ref` = the aggregate id
   from the payload (`invoiceId`, `walletId`, ...); `data` only for small scalars the UI needs (a status or a count),
   never an amount, a name, an email or a free-text field.
3. High-rate events: an event type that can exceed roughly 10 per second for one tenant in normal operation gets no
   signal unless a console screen must follow it live; the exclusion is recorded in the no-signal registry with a reason
   (decided with measurements in this phase; default: `usage.rated` is excluded, `wallet.*` and `charge.*` map to the
   wallet reference).
4. `exports` gains `realtime.push -> realtime.commands` in both `MessagingProperties`; `OutboxRoutes` stays valid.
5. `declare-users.sh`: `billing` and `survey` gain `write` on `realtime.commands` and nothing else new.
6. Retention predicate: both apps' outbox cleanup keeps rows with an unfinished broker cursor.
7. Each app has a coverage test that calls `SignalCoverage` with its event directory, its mappings and the registry.

## Files

Modify under `backend/services/apps/billing/` and `backend/services/apps/survey/`:
- `apps/app-boot/.../config/MessagingConfiguration.java` and `OutboxConfiguration.java`
- a new `RealtimeConfiguration.java` (decorator and `SignalMappings`) in each `boot/config`
- the outbox cleanup job and its test
- new tests: `SignalCoverageTest`, mapping tests for each mapped type reading the real schema fixtures

Also modify: `ops/rabbitmq/declare-users.sh`.

## Steps

1. List every event type per app from `contracts/events/<app>/` and the read permissions from the permission catalogs;
   write the mapping table (event, permission, ref path, fields) in the test resources, not only in code.
2. Measure or reason about each billing type's rate from the existing load test (`ops/loadtest/`) and docs; decide
   exclusions and record reasons.
3. Implement the mappings and the decorator wiring; keep the decorator the only publisher entry point so no event bypasses
   it.
4. Add the export entries and confirm the broker relay test in each app still passes
   (`OutboxRelayIntegrationTest`, `InvoiceBrokerPublishIntegrationTest` for billing).
5. Add the coverage test and mapping tests: for a sample event of each mapped type, the signal row has the expected
   audience, ref and fields, and no field outside the allowed list.
6. Add the retention test for the broker cursor.
7. Update `declare-users.sh`.

## Todo

- [ ] Mapping tables for Billing and Survey
- [ ] Decorator and mappings wired in both apps
- [ ] Export entries in both
- [ ] Exclusions decided and recorded with reasons
- [ ] Coverage and mapping tests
- [ ] Retention tests
- [ ] Broker grants

## Verification

- `cd backend/services/apps/billing && ./gradlew build` and `cd backend/services/apps/survey && ./gradlew build` pass.
- The coverage test fails if a billing or survey event type is removed from both mappings and the registry (checked by
  temporarily removing one and seeing the failure, then restoring it).
- Existing webhook and broker publish tests are unchanged and pass (the in-process relay never sees a signal row).

## Risks

- A mapping that points at a renamed payload field fails the transaction and would block a business operation. The
  mapping tests read the real schemas so a rename breaks the build, not production.
- Over-signalling billing noise: exclusions plus server coalescing bound the cost; revisit after phase 13 numbers.

## Security

- No amount, name or address enters a signal; a test asserts that every mapped field is on an allow-list.
- A reader without the permission cannot learn that an event happened, because realtime filters by the code the producer
  names.

## Next

Phase 10.
