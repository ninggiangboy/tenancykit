---
phase: 5
title: "Core messaging contract"
status: pending
priority: P1
effort: M
dependencies: [1]
---

# Phase 5: Core messaging contract

## Goal

Fix the envelope codec, stop holding database locks across broker confirms, make the two relays share one
implementation, and end the snake_case/camelCase contract drift.

## Findings addressed

- `EnvelopeCodec.java:46` passes `type` as `aggregate`; `Wire` has no `aggregate`; `encode` drops it. Every
  cross-service event reports `aggregate == type`.
- `EnvelopeCodec` parses `schema_version` but never validates it.
- The AMQP document is `id, type, tenant_id, occurred_at, schema_version, data`; the webhook document is
  `id, type, tenantId, occurredAt, data`. `contracts/messaging/README.md` and `contracts/events/README.md` both claim
  they are the same document.
- `BrokerRelay.java:139,157-174` holds `for update skip locked` rows and a pooled connection across
  `waitForConfirmsOrDie(10s)` inside the database transaction; the worker `lock_timeout` is 5s.
- `OutboxRelay` and `BrokerRelay` duplicate the row mapping, claim, reason clipping and failure recording; only
  `OutboxRelay` recognises `55P03/40P01` contention, and only `OutboxRelay` takes a configurable jitter.
- `JooqAuditLog` sits in `core.messaging.audit`; `JooqEventInbox` sits in the `outbox` package.
- `Text.clip` exists but both relays hand-roll the 500-char truncation.

## Files to create / modify

- Modify `core/core-messaging/.../broker/EnvelopeCodec.java`: carry `aggregate` on the wire; validate
  `schema_version`; reject an unknown major version; add encode→decode round-trip tests.
- Create `core/core-messaging/.../broker/EventDocument.java` (or `SuiteEnvelope`): one canonical camelCase writer/reader
  (`id, type, tenantId, occurredAt, schemaVersion, data`) used by both the broker and the webhook body writer.
- Modify `core/core-messaging/.../broker/BrokerRelay.java`: claim with a `claimed_until` deadline in a short
  transaction, publish outside it, mark `PUBLISHED` in a second transaction; detect contention like `OutboxRelay`.
- Create `core/core-messaging/.../outbox/OutboxRows.java`: shared `Row`, `claim`, `toEnvelope`, `failureReason`,
  `Text.clip` and metrics helper; `OutboxRelay` and `BrokerRelay` use it.
- Modify `core/core-messaging/.../config/MessagingProperties.java`: add the jitter, the claim deadline and the
  document format; remove the hard-coded `0.2`.
- Move `core/core-messaging/.../audit/JooqAuditLog.java` to `core-spring` (or a small `core-audit`); move
  `JooqEventInbox` to a `messaging.inbox` package.
- Modify `contracts/messaging/README.md` and `contracts/events/README.md`: one envelope description and the
  compatibility rule.
- Modify `billing/contexts/webhook/.../adapter/delivery/json/JacksonEventBodyWriter.java` to use the canonical
  writer; add a test that the AMQP and webhook documents are equal for one event.
- Modify `core/core-messaging/.../outbox/OutboxRelay.java` to use `OutboxRows` and the configured jitter.

## Tasks

1. Add a failing round-trip test: `aggregate` must survive encode/decode, `schema_version = 2` must be rejected by a
   v1 consumer.
2. Add `aggregate` to the wire and `schema_version` validation; convert the wire to the canonical camelCase document,
   bump `schemaVersion`, and update every in-repo producer, consumer, contract and smoke assertion in the same
   change (D1, D9: one breaking release, no dual-format window).
3. Split the broker claim/publish/mark into two transactions with a claim deadline; add a slow-broker test that proves
   another worker is not blocked.
4. Extract `OutboxRows`; make both relays use the same contention policy, failure reason and metric names.
5. Move the audit/inbox classes and update imports.
6. Make the webhook body writer and the broker codec produce the same document; update the contracts and the docs.
7. Add tests for poison messages, contention (`55P03`) and the claim expiry.

## Verification

- `cd core && ./gradlew build`
- `cd core && ./gradlew :core-messaging:test`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*OutboxRelayIntegrationTest' --tests '*BrokerRelayIntegrationTest' --tests '*EventContractIntegrationTest'`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*WebhookDeliveryIntegrationTest' --tests '*InvoiceBrokerPublishIntegrationTest'`

## Success criteria

- [x] An event encoded and decoded has the same `eventId`, `tenantId`, `type`, `aggregate`, `occurredAt` and payload.
- [x] A broker that never confirms does not hold a row lock longer than the claim deadline; a second worker makes
      progress.
- [x] Both relays share one failure/contention/metrics implementation.
- [x] The AMQP and webhook documents for one event are byte-equal (or the documented compatibility mapping is tested).
- [x] Contracts and code agree on field names and `schema_version`.

## Risks

Changing the wire format is a coordinated release; D9 allows the break. Mitigation: change producer, every in-repo
consumer, the contracts and the smoke test in one change, bump `schemaVersion`, and deploy all services together.
