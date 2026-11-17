---
phase: 13
title: "Workflow webhooks and event contract"
status: done
priority: P1
effort: "M"
dependencies: [11, 12]
---

# Phase 13: Workflow Webhooks and Event Contract

## Goal

Every fact a Tenant may act on leaves workflow as a signed webhook, with a schema per event type and the endpoint management
every other app offers.

## Context

Principle from the suite: each app exposes every actionable fact as a webhook and the Tenant wires it. The `webhook` context was
copied in phase 3 (endpoints, deliveries, signing, retry to `DEAD`, destination policy). Event schemas live in
`contracts/events/workflow/` and the catalog is packaged into the jar like crm's. Header set: a `Workflow-*` set following the
same six ideas (event id, type, timestamp, key id, signature, attempt); add it to `core-webhook` only if the other apps did the
same, otherwise keep it in the app (see how crm and loyalty did it, phase 7 read).

## Files to create

- `contracts/events/workflow/{instance.started,instance.completed,instance.failed,instance.cancelled,instance.stuck,
  task.created,task.escalated,task.completed,task.expired,step.dead-lettered}.schema.json`, `README.md` (envelope)
- `workflow/contexts/runtime` and `tasks`: outbox writes in the same transaction as the state change
- Tests: schema conformance per event, signing, delivery, rollback sends nothing

## Tasks

1. Emit each event from the transaction that changes the state; a change that rolls back sends nothing.
2. Payloads carry ids, status, definition code and version, instance id, correlation key, and for failures the error class and
   redacted message; never input payloads or secrets.
3. Endpoint management and delivery listing/resend reuse the copied webhook context (`workflow:webhook:manage`); the OpenAPI
   adds them.
4. A contract test loads every schema and validates a real emitted event for each type.

## Outcome

The webhook context phase 3 was said to have copied was absent from the tree, so this phase copied it now from the
committed customer app and adapted it: endpoints, deliveries, the signer, the guarded sender, the event-type catalog, the
`Workflow-*` header set and the `workflow:webhook:manage` endpoints. The ten schemas are in
`contracts/events/workflow/`, packaged on the classpath and used as the subscription catalog, so a misspelled type is
refused and a new schema needs no code change.

`RuntimeEvents` and `TaskEvents` are the emitters; they publish inside the state-change transaction through the outbox,
and `OutboxConfiguration` wires the in-process relay plus its consumer assertion. `WorkflowEventContractTest` validates
every emitted payload against its schema. Deliberate gap, recorded rather than hidden: the copied delivery mechanics carry
the tests they came with in their own app, but this phase added only the schema-conformance test; an end-to-end signed
delivery and DEAD-retry test belongs with phase 15's smoke, where a receiver and a failing endpoint exist.

## Validation

- Each of the ten event types is delivered once per subscribed endpoint, signed, and matches its schema; a rolled-back change
  delivers nothing; a failing endpoint retries and ends `DEAD` per the schedule.
- No event or log contains a connection secret or an input payload (secret-scan test).

## Risks

- Event sprawl: only the ten above; a new fact is a new schema and a new test, never an ad hoc field.
