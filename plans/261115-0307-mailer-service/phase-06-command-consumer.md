---
phase: 6
title: "Command Consumer"
status: pending
priority: P1
effort: "M"
dependencies: [4, 5]
---

# Phase 6: Command Consumer

## Goal

Turn one message on `mailer.commands` into validated ledger rows, inside a unit of work, with inbox dedupe,
no network call to SES, and a defined answer for a duplicate `requestId`.

## Key Insights

- `AmqpInboundDispatcher` (`core/core-messaging/src/main/java/dev/ngb/core/messaging/inbound/AmqpInboundDispatcher.java:36-45`)
  takes **six** constructor arguments:
  `(EnvelopeCodec codec, List<InboundEventHandler> handlers, EventInbox inbox, UnitOfWork unitOfWork, TenantContext tenantContext, RetryTemplate retry)`.
  `EnvelopeCodec` is not a bean anywhere in `core` — Billing constructs it by hand from its `JsonMapper`
  (`billing/.../boot/config/ReplicaConfiguration.java:159`). Miss it and the context fails at startup with
  `UnsatisfiedDependencyException`.
- `MessagingProperties` is a record with five components; `relay` and `document` are `requireNonNull`
  (`core/core-messaging/src/main/java/dev/ngb/core/messaging/config/MessagingProperties.java:31-36`). The
  envelope version is **2** (`.../broker/EventDocument.java:23`), and a wrong value rejects every command
  (`:55-57`). State it explicitly.
- Because the handler runs inside the dispatcher's transaction, calling SES there would hold a database
  transaction open across a network call and would resend on retry. The handler writes rows and returns.
- **A schema-invalid message cannot be made to skip retries as `handle()` is written.** `handle` declares no
  checked exception (`InboundEventHandler.java:15`), the dispatcher catches `RuntimeException` and wraps it in
  `AmqpRejectAndDontRequeueException` after `retry.execute` (`:60-68`), and `defaultRetry()` is
  `.retryOn(RuntimeException.class)` with 3 attempts (`:81-96`). So every handler failure is retried three
  times. Two workable answers, pick one and say so:
  - **(a) Accept the retries.** A schema failure is retried 3 times and then dead-letters. It is cheap
    because nothing was written (the inbox and ledger are written in the same transaction that fails), and it
    needs no new wiring. Assert `email_delivery_attempts` has 0 rows and the DLQ has 1 message.
  - **(b) Set `setDefaultRequeueRejected(false)` on mailer's own `SimpleMessageListenerContainer`** and use a
    custom `RetryTemplate` with a classifier that excludes `InvalidEmailCommandException`. Billing's container
    never sets that flag (`ReplicaConfiguration.java:130-135`), so this is mailer-specific wiring.
  Do **not** write "throw a checked exception": it cannot compile, and a sneaky-thrown non-`RuntimeException`
  escapes the dispatcher's catch and then requeues forever under Spring AMQP's default
  `defaultRequeueRejected=true`.
- **A duplicate is not automatically a no-op.** The inbox key is `(tenant_id, consumer, event_id)`; the
  ledger key is `(tenant_id, request_id, recipient)`. A producer that times out and retries sends a *new*
  event id with the *same* `requestId`: the inbox says "first time", the ledger insert conflicts, and
  `onConflict().doNothing()` silently drops the command. If the existing row is `DEAD`, the plan's promise
  that a body-plus-ledger split "makes `DEAD` resendable" is false unless the handler treats that case
  specially. Define it:
  - all matching rows `SUCCEEDED` or `IN_PROGRESS` → no-op, log at INFO with the request id;
  - any matching row `DEAD` → resurrect it (`PENDING`, attempts reset, `completed_at` cleared,
    `last_error` cleared) and log at WARN with the request id;
  - mixed → no-op for the live rows, resurrect only the dead ones.
  `handle` is `void`, so the outcome is a log line and a metric, not a return value.
- Mailer publishes nothing, so `exports` is an empty map and no outbox table is needed.

## Files to Create / Modify

- Modify: `mailer/apps/app-boot/build.gradle.kts` — `processResources` copies `../../contracts/commands`
  into `commands/` on the classpath (Billing's block is at
  `billing/apps/app-boot/build.gradle.kts:59-69`, not `:64-78`)
- Create: `mailer/apps/app-boot/src/main/java/dev/ngb/mailer/boot/infrastructure/InfrastructureConfiguration.java`
  — `TenantContext`, `UnitOfWork` + `MailerTransactionScopeHook`, `EventInbox` (`JooqEventInbox`), `Clock`,
  `IdGenerator`
- Create: `.../boot/infrastructure/tenancy/MailerTransactionScopeHook.java` — copy of
  `billing/.../infrastructure/tenancy/BillingTransactionScopeHook.java:26-31`
- Create: `.../boot/config/MessagingConfiguration.java` — `JsonMapper` → `EnvelopeCodec`,
  `MessagingProperties(exchange="mailer.commands", exports=Map.of(), queues=[QueueBinding("mailer.commands",
  "mailer.commands", ["email.send"])], relay=Relay(...), document=Document(2))`, `Declarables` from
  `SuiteTopology.declarables`, `AmqpInboundDispatcher`, listener container (`@Profile("worker")`)
- Create: `mailer/contexts/email/src/adapter/.../email/messaging/EmailCommandHandler.java`
- Create: `.../email/messaging/CommandSchemaValidator.java`
- Create: `.../email/messaging/InvalidEmailCommandException.java`
- Create: `.../email/persistence/JooqEmailDeliveryRepository.java`
- Create: `mailer/contexts/email/src/application/.../email/port/EmailDeliveryRepository.java`
- Create test: `mailer/apps/app-boot/src/test/java/dev/ngb/mailer/email/EmailCommandConsumerIntegrationTest.java`

## Architecture

```
AMQP body
  → AmqpInboundDispatcher.decode            (EnvelopeCodec, from JsonMapper)
  → tenant scope bound from envelope        (core)
    → EmailCommandHandler.handle
        → CommandSchemaValidator.validate   (failure → InvalidEmailCommandException)
        → inbox.firstTime("mailer.email-send", event)          (core, same UoW)
        → per recipient: insert ledger (PENDING) + body row
             conflict → APPLY THE DUPLICATE POLICY, never silently drop
    → commit
  → ack
```

One consumer name, `mailer.email-send`, asserted in a test: two handlers sharing a name would make the
inbox drop one of them.

## Implementation Steps

1. Add the `processResources` block and `json-schema-validator` (already `libs.json-schema-validator`).
2. Write `CommandSchemaValidator`: load `commands/email.send.schema.json` once, validate `event.payload()`,
   throw `InvalidEmailCommandException` on failure. Fail at startup if the resource is missing, so a
   packaging mistake is a boot failure, not a per-message surprise.
3. Write `JooqEmailDeliveryRepository`: insert one ledger row and one body row per recipient with
   `onConflict().doNothing()`, plus a method that reads the existing rows for the same
   `(tenant_id, request_id, recipient)` set so the handler can apply the duplicate policy.
4. Write `EmailCommandHandler`: validate → decode → apply the duplicate policy → insert. Log at INFO for a
   no-op duplicate, WARN for a resurrection, and never log the body.
5. Wire `InfrastructureConfiguration` (the only class declaring `clock`/`idGenerator`) and
   `MessagingConfiguration` with all six dispatcher arguments and `Document(2)`.
6. Register the listener container under `@Profile("worker")` on queue `mailer.commands`. If you chose
   option (b), set `defaultRequeueRejected(false)` here and document why.
7. Write the integration test with Testcontainers Postgres + RabbitMQ: valid command → one `PENDING` row per
   recipient; same event id twice → still one row per recipient; same `requestId` with a new event id and the
   row `SUCCEEDED` → no change; same `requestId` with the row `DEAD` → resurrected to `PENDING`;
   schema-invalid command → zero rows and one DLQ message.

## Todo

- [ ] `processResources` packages `contracts/commands`
- [ ] `CommandSchemaValidator` + `InvalidEmailCommandException`, failing at startup if the resource is missing
- [ ] `JooqEmailDeliveryRepository` with `onConflict().doNothing()` and an existing-rows read
- [ ] Duplicate policy: no-op for live rows, resurrect `DEAD`, WARN with the request id
- [ ] `InfrastructureConfiguration` (sole owner of `clock`/`idGenerator`, scope hook, `JooqEventInbox`)
- [ ] `MessagingConfiguration`: `EnvelopeCodec`, full `MessagingProperties` incl. `Document(2)`, `Declarables`, dispatcher
- [ ] Listener container on `mailer.commands`, `@Profile("worker")`, retry choice (a) or (b) documented
- [ ] Integration test: valid, replay, duplicate-live, duplicate-dead, schema-invalid

## Verification

```bash
cd mailer && ./gradlew :apps:app-boot:test --tests '*EmailCommandConsumerIntegrationTest'
cd mailer && ./gradlew build
```

Assert on counts, not on the absence of an exception:

```java
assertThat(countDeliveries(tenantId, requestId)).isEqualTo(2);              // two recipients
assertThat(countDeliveries(tenantId, requestId)).isEqualTo(2);              // after replay: unchanged
assertThat(statusOf(tenantId, requestId)).isEqualTo(DeliveryStatus.PENDING); // after DEAD resurrection
```

## Risk Assessment

| Risk | Signal it broke | Response |
|---|---|---|
| `EnvelopeCodec` not wired | `UnsatisfiedDependencyException` at startup | Construct it from the `JsonMapper` in `MessagingConfiguration` |
| Wrong `Document(schemaVersion)` | Every command dead-letters; log says "unsupported suite envelope schema_version" | It is 2; assert it in a test |
| "Non-retryable exception" written as specified | Cannot compile; the checked variant requeues forever | Choose option (a) or (b) explicitly |
| Duplicate silently dropped | A producer's retry produces no email and no row, and the log is empty | Apply the status-aware duplicate policy |
| Two `@Configuration` classes declaring `clock` | `BeanDefinitionOverrideException` | Only `InfrastructureConfiguration` declares them |
| Handler calls SES | Stopping Mailpit keeps a transaction open and duplicates on retry | Handler is network-free |
| `commands/` absent from the image | Boot fails on a missing resource, only in ECS | Phase 5 copies `contracts/` into the Dockerfile |

## Security Considerations

- The payload is untrusted input: validate against the schema before writing, and validate `Recipient`/
  `FromKey` again in the value objects.
- `fromKey` is resolved server-side in phase 7/8; a producer cannot select an arbitrary sender address.
- The `tenantId` in the envelope is **asserted by the publisher, not authenticated**. Anything with `write`
  on `mailer.commands` can name any tenant. Record that as a documented trust boundary in
  `contracts/commands/README.md` (phase 1) and reject the platform tenant id on this path, since mailer has no
  legitimate platform-scope send.
- Log the request id and recipient count, never the body.
