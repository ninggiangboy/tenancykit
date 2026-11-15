---
phase: 2
title: "Mailer: result events, headers, tenant registration"
status: done
priority: P1
effort: "M"
dependencies: [1]
---

# Phase 2: Mailer — Result Events, Headers, Tenant Registration

## Goal

Mailer publishes `email-delivery.changed` for every terminal outcome, accepts an allowlisted `headers` field, and
registers a tenant the first time it accepts a command for it.

## Context

- `mailer/contexts/email/src/application/java/dev/ngb/mailer/email/application/email/command/DeliverEmailsUseCase.java`
  — `writeOutcome` decides the status and calls `claims.recordOutcome` in its own transaction.
- `mailer/contexts/email/src/application/.../command/AcceptEmailCommandUseCase.java` — accepts a command in one transaction.
- `mailer/apps/app-boot/src/main/java/dev/ngb/mailer/boot/config/MessagingConfiguration.java` — `MessagingProperties`
  with `exports = Map.of()` and the comment "publishes nothing, hence no outbox table".
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/config/OutboxConfiguration.java` — the wiring to copy.
- `core/core-messaging/.../outbox/JooqOutboxAppender.java`, `BrokerRelay.java`, `SuiteTopology.java`.
- `mailer/apps/app-boot/src/main/resources/db/migration/V2__delivery.sql`, `V6__platform_tenant.sql`.

## Key insights

- The tenant gap is real: `mailer_tenants` is written only by migrations, and `TenantSweep` reads it. Notification mail
  for an ordinary tenant is accepted and never delivered today. This phase fixes it for everyone, not just `email/`.
- `MessagingProperties.exchange` is the **producing** exchange. Mailer currently passes `mailer.commands` there only
  because it produces nothing. Set it to `mailer.events`; `SuiteTopology` still declares `mailer.commands` through the
  queue binding.
- Resurrecting a dead delivery returns it to `PENDING`, and a later success must still reach the consumer. The event
  therefore carries `completedAt`, and the consumer keeps the newest.

## Files to create / modify

- Create: `mailer/apps/app-boot/src/main/resources/db/migration/V7__outbox.sql` (outbox table, same DDL as notification's baseline)
- Create: `mailer/apps/app-boot/src/main/resources/db/migration/V8__headers.sql` (`headers jsonb` on the body table)
- Modify: `mailer/apps/app-boot/.../config/MessagingConfiguration.java` (producing exchange, `exports`, remove the comment)
- Create: `mailer/apps/app-boot/.../config/OutboxConfiguration.java`, `boot/jobs/OutboxRelayJob.java`
- Modify: `mailer/apps/app-boot/.../config/MailerSettings.java` and `application*.yml` (`messaging.events-exchange`)
- Create: `mailer/contexts/email/src/application/.../port/DeliveryResultPublisher.java` and an adapter using `EventPublisher`
- Modify: `DeliverEmailsUseCase.java` (publish inside the `recordOutcome` transaction, only on `SUCCEEDED` / `DEAD`)
- Modify: `AcceptEmailCommandUseCase.java` plus a `MailerTenants.register` port method (insert `on conflict do nothing`)
- Modify: `CommandSchemaValidator`, `EmailCommandHandler`, `OutgoingEmail`, `SesV2EmailSender`, `SmtpEmailSender`
  (carry `headers`; SES v2 `Content.Simple.Headers`; SMTP message headers)
- Modify: `ops/rabbitmq/declare-users.sh` (mailer user may write `mailer.events`) and `ops/smoke/isolation.sh`
- Modify: `docs/mailer.md`, `docs/suite-messaging.md`

## Tasks

1. Add the outbox migration and the outbox beans; `exports = {"email-delivery.changed": "mailer.events"}`; keep the
   startup assertion that `exports` is not empty.
2. Publish from the delivery use case in the same transaction as `recordOutcome`, so a crash cannot record an outcome
   and lose its event. The CAS on the attempt count already prevents a stale worker from publishing a second result.
3. Register the tenant inside the accept transaction. `mailer_tenants` has no RLS, so the insert needs no scope.
4. Add `headers`: validate against the schema, store, pass to both senders. Keep the field optional so notification's
   commands are unchanged.
5. Broker permissions: mailer may publish only to `mailer.events` (and keeps reading its own queues). Update the
   isolation smoke: mailer cannot publish to `platform.events`, `billing.events` or `mailer.commands`.
6. Rewrite the "publishes nothing" paragraphs in `docs/mailer.md` and the topology table.

## Verification

- Integration test: send a command, run a delivery round, assert exactly one outbox row with the right status; make the
  send reject, assert a `DEAD` event; make it transient, assert **no** event until the budget is exhausted.
- Integration test: a command for a tenant with no `mailer_tenants` row is delivered in the next round.
- `EmailCommandContractTest` in `notification/` still passes (additive schema change).
- `SesWireContractTest` asserts the headers reach the SES request; the SMTP fake server sees them.
- `./gradlew build` in `mailer/` and `notification/`; `ops/smoke/isolation.sh` against the compose broker.

## Risks and rollback

- A wrong envelope `schemaVersion` rejects every event. Use `Document(2)` and assert it in a test.
- Rollback: the new migrations are additive; disable the relay job (`mailer.jobs.enabled`) and the events stop with no
  effect on delivery. Back up the mailer database before applying V7 and V8 in any shared environment.
