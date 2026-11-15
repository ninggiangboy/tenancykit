---
title: "Phase 6: Email Publisher, Outbox and Dispatch Ledger"
status: todo
---

# Phase 6: Email Publisher, Outbox and Dispatch Ledger

## Context Links

- `contracts/commands/email.send.schema.json` — the payload this phase must produce exactly
- `core/core-messaging/src/main/java/dev/ngb/core/messaging/outbox/{JooqOutboxAppender,OutboxRelay}.java`,
  `.../broker/BrokerRelay.java`, `.../config/MessagingProperties.java:54`
- `billing/apps/app-boot/src/main/resources/db/migration/V2__platform_tables.sql:3-16` — the outbox DDL and its index
- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/jobs/OutboxJobs.java` — the relay job shape
- `mailer/contexts/email/src/adapter/java/dev/ngb/mailer/email/adapter/delivery/messaging/CommandSchemaValidator.java`
- `docs/mailer.md` — the ledger's status machine and the resurrection rule

## Overview

Priority P1, size L. Adds the email half without touching mailer: for each accepted notification the service writes a
dispatch row and an outbox row in the same transaction that stored the in-app notification, and a worker job relays the
outbox to `mailer.commands` as an `email.send` command carrying the already-rendered subject, HTML and text.

The dispatch ledger is what makes the service answerable. A user who says "I never got an email" gets one of four
answers from one table: it was published, it was muted by preference, it was skipped because the account is disabled,
or it was suppressed inside the coalescing window.

## Key Insights

- **The outbox is not optional here.** Publishing from the event handler would send an email even when the transaction
  that stored it rolls back, and a broker outage would lose it. `JooqOutboxAppender` writes inside the caller's
  transaction and `BrokerRelay` publishes after commit, which is exactly the suite's at-least-once contract.
- `requestId` must be deterministic and stable across retries: the dispatch row's own id. A relay retry, a redelivered
  event, or a manual resend then all resolve to the same mailer ledger rows instead of new emails.
- Building the payload with a hand-written map and hoping it matches the contract is how a producer drifts. The factory
  validates its own output against the packaged `commands/email.send.schema.json` before appending the outbox row, so a
  mismatch fails in this service rather than in mailer's dead-letter queue.
- **No coalescing window.** A role edit emits one `user-access.changed` per affected user, so a burst is one email per
  *different* user, not many to one. Suppressing repeats for the same user would need a lock or a conditional insert,
  and a racy window that silently drops mail is worse than the rare duplicate. The unique dispatch key is the dedupe.
- `fromName` is tenant-controlled text and the contract refuses quotes, angle brackets, commas and line breaks. The
  factory sanitises it, and **omits the property entirely when nothing survives** — `fromName` is optional in the
  contract, while an empty string fails `minLength: 1` and would roll back the dispatch, retry three times and
  dead-letter that tenant's whole stream.
- A user who became `DISABLED` is skipped for email but keeps the in-app row from Phase 5. That asymmetry is recorded
  on the dispatch row as `SKIPPED_INACTIVE`, not left implicit.
- Mailer owns delivery truth. The dispatch row stops at `PUBLISHED`; it must never claim `DELIVERED`, because nothing in
  this service knows what SES did.

## Requirements

Functional

- One `email_dispatches` row per `(tenant, source event, type, user)` with the final status and, when skipped, the
  reason.
- One `outbox` row per published dispatch, in the same transaction.
- The outbox relay publishes `email.send` to `mailer.commands` with routing key `email.send` and marks the row
  `DELIVERED` only after the broker confirms.
- Skip rules, evaluated before the outbox row: muted type → `SUPPRESSED_MUTED`; blank address →
  `SKIPPED_NO_ADDRESS`; current status `DISABLED` → `SKIPPED_INACTIVE`.
- The payload matches `contracts/commands/email.send.schema.json`, with `fromKey` from configuration and `fromName` the
  tenant's name, sanitised and omitted when it sanitises to nothing.

Non-functional

- The handler never calls the broker directly; only the relay does, from the worker profile.
- `notification.email.enabled = false` keeps rows `PENDING` in the outbox and sends nothing.
- The relay is bounded: batch size, max attempts, backoff and claim deadline from `MessagingProperties.Relay`.

## Architecture

```
AcceptChangeUseCase (transaction)
  ├─ notifications insert (Phase 5)
  └─ EmailDispatchUseCase
        ├─ skip rules  → notification_dispatches(status = SKIPPED_*/SUPPRESSED_*)
        └─ EmailCommandFactory (validates against commands/email.send.schema.json)
              → outbox row (status PENDING, type email.send, aggregate notification/<dispatchId>)
                                         │
     worker: OutboxRelayJob ── BrokerRelay.relayDue() ──▶ mailer.commands / email.send
                                         └─ outbox status DELIVERED after broker confirm
```

Tables:

```sql
create table email_dispatches (
    id uuid primary key, tenant_id uuid not null, source_event_id uuid not null,
    notification_type text not null, user_id uuid not null,
    recipient text, request_id text not null, from_key text not null,
    template_key text not null, template_version integer not null, locale text not null,
    subject text, status text not null
        check (status in ('PENDING','PUBLISHED','SUPPRESSED_MUTED',
                          'SKIPPED_INACTIVE','SKIPPED_NO_ADDRESS','DEAD')),
    reason text, created_at timestamptz not null, published_at timestamptz,
    unique (tenant_id, source_event_id, notification_type, user_id));
create index email_dispatches_user_type_idx
    on email_dispatches (tenant_id, user_id, notification_type, created_at desc);
call enable_tenant_rls('email_dispatches');
```

`outbox` is **not** the short table from Billing's `V2__platform_tables.sql:3-16`. `BrokerRelay.claim` predicates on
`publish_status` and `publish_next_at`, which Billing added later in `V22__outbox_broker_cursor.sql:3-11`. Copy the
final shape: `id`, `tenant_id`, `aggregate`, `type`, `payload jsonb`, `created_at`, `published_at`, `publish_status`,
`publish_attempts`, `publish_next_at`, `last_error`, plus both indexes (`outbox_pending_idx` and
`outbox_publish_pending_idx`) and `call enable_tenant_rls('outbox')`. A `V2`-shaped copy fails on the first relay tick
with `column publish_next_at does not exist`. Owned by `boot` in `TABLE_OWNER`.

## Related Code Files

Create

- `notification/contexts/dispatch/src/domain/java/dev/ngb/notification/dispatch/domain/notification/{EmailDispatch,EmailDispatchStatus}.java`
- `notification/contexts/dispatch/src/application/java/dev/ngb/notification/dispatch/application/notification/command/EmailDispatchUseCase.java`
- `notification/contexts/dispatch/src/application/java/dev/ngb/notification/dispatch/application/notification/port/{EmailDispatchRepository,EmailCommandFactory,NotificationEmailCommand}.java`
- `notification/contexts/dispatch/src/adapter/java/dev/ngb/notification/dispatch/adapter/notification/persistence/JooqEmailDispatchRepository.java`
- `notification/contexts/dispatch/src/adapter/java/dev/ngb/notification/dispatch/adapter/notification/email/{ContractEmailCommandFactory,EmailCommandSchemaValidator}.java`
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/jobs/{OutboxRelayJob,HousekeepingJob}.java`
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/config/OutboxConfiguration.java`
- `notification/contexts/dispatch/src/application/java/dev/ngb/notification/dispatch/application/notification/command/HousekeepingUseCase.java`
- `notification/apps/app-boot/src/main/resources/db/migration/V4__email_dispatch.sql`
- `notification/apps/app-boot/src/test/java/dev/ngb/notification/dispatch/{EmailCommandContractTest,EmailDispatchUseCaseTest,EmailSkipRulesTest,OutboxRelayIntegrationTest,HousekeepingIntegrationTest}.java`

Modify

- `notification/apps/app-boot/build.gradle.kts` — copy `contracts/commands/*.schema.json` into `commands/` (the same
  `processResources` block mailer uses)
- `notification/contexts/dispatch/src/application/java/.../command/AcceptChangeUseCase.java` — call
  `EmailDispatchUseCase` inside the same unit of work
- `notification/apps/app-boot/src/main/resources/application.yml` — `notification.email.*`,
  `notification.housekeeping.*`, and `notification.messaging.exports` mapping `email.send → mailer.commands`
- `notification/apps/app-boot/src/test/java/dev/ngb/notification/architecture/ArchitectureRules.java` — `Outbox` under
  `boot`, the two dispatch tables under `dispatch`
- `notification/libs/jooq-schema` — regenerated

## Implementation Steps

1. Write `V4__email_dispatch.sql` with the dispatch table above, then the outbox in its **final** shape (see the note
   under the DDL), including both partial indexes and RLS.
2. Write `EmailDispatch` and its status enum. Keep `PUBLISHED` distinct from `DELIVERED`: the first is this service's
   statement, the second would be a claim it cannot support.
3. Write `ContractEmailCommandFactory`: build the `data` map (`requestId`, `to`, `fromKey`, `fromName`, `subject`,
   `html`, `text`), validate it with `json-schema-validator` against the packaged `commands/email.send.schema.json`,
   then return a `NotificationEmailCommand` carrying the payload and the aggregate `notification/<dispatchId>`.
   Sanitise `fromName` by removing `"`, `<`, `>`, `,` and line breaks, truncate to 128, and **omit the key when the
   result is empty**. On validation failure throw a fixed-message exception: the validator's message can embed the
   offending value, and an address must not travel into `outbox.last_error` or the relay's log.
4. Write `EmailDispatchUseCase`: evaluate the three skip rules in the order listed (muted, no address, inactive), insert
   the dispatch row, and on the publish path call `EventPublisher.publish(...)` with a `DomainEvent` whose `eventId` is
   the dispatch id, `type` is `email.send`, `tenantId` the tenant, `aggregate` `notification/<dispatchId>` and payload
   the command's data. Everything stays inside the caller's transaction.
5. Wire `OutboxConfiguration`: `JooqOutboxAppender` as `EventPublisher`, `OutboxRelay`, `BrokerRelay`, and the
   `MessagingProperties.exports` entry that routes `email.send` to `mailer.commands` (`BrokerRelay` resolves the
   exchange from `exports` and uses the outbox row's `type` as the routing key). Assert `exports` is non-empty at
   startup: `relayDue()` returns a zero result when the route set is empty, so a misnamed property would make the relay
   a silent no-op. Declare the relay tuning in `MessagingProperties.Relay`.
6. Write `OutboxRelayJob` (`@Profile("worker")`, `@ConditionalOnProperty(notification.email.enabled)`) modelled on
   `OutboxJobs`: `sweep.forEachActiveTenant(tenant -> brokerRelay.relayDue())` with the poll delay from configuration.
   Notification has no in-process consumers, so only the broker relay is called.
7. Write `HousekeepingJob` and `HousekeepingUseCase`: bounded deletes for `notifications` past
   `notification.housekeeping.notification-retention` (default `P90D`), `email_dispatches` past `P90D` (keeping the row,
   clearing `reason`/`subject`), `event_inbox` past `P30D`, and `DEAD` outbox rows past `P7D`. Mailer sets the
   precedent — `event_inbox.processed_at` exists for exactly this — and without a job the in-app bodies grow forever.
8. Log one line per dispatch with `requestId`, type, recipient count and status; never log the body or an address.
9. Tests:
   - `EmailCommandContractTest` builds a command for all five types and validates each against the real contract file;
     mutate a field (drop `to`, add a newline to `subject`) and assert the factory refuses; a tenant name of `"<>,,"`
     produces a payload with no `fromName` key and still validates.
   - `EmailSkipRulesTest` covers muted, blank address and disabled user, asserting no outbox row and the recorded status
     and reason.
   - `EmailDispatchUseCaseTest` asserts `requestId` equals the dispatch id and that a repeated call for the same
     `(tenant, source event, type, user)` inserts nothing new.
   - `OutboxRelayIntegrationTest` runs against a real broker: one row is published once to `mailer.commands` with
     routing key `email.send`, the outbox row becomes `DELIVERED`, and a second relay round publishes nothing.
   - `HousekeepingIntegrationTest` seeds rows past each retention boundary and asserts they are deleted or redacted while
     newer rows survive.

## Todo

- [ ] Dispatch table, outbox table (final shape) and migration
- [ ] `EmailDispatch` aggregate and status vocabulary
- [ ] Contract-validating command factory with `fromName` sanitisation and omission
- [ ] Skip rules with recorded reasons
- [ ] Outbox wiring, non-empty `exports` assertion and worker relay job
- [ ] Housekeeping job with the four retention boundaries
- [ ] `email.send → mailer.commands` routing declared
- [ ] Contract, skip-rule, dedupe, relay and housekeeping tests

## Success Criteria

- `cd notification && ./gradlew :apps:app-boot:test --tests '*EmailCommandContract*' --tests '*EmailSkipRules*'
  --tests '*OutboxRelay*' --tests '*Housekeeping*'` passes.
- The contract test fails when `to`, `fromKey` or `subject` is removed from the payload, proving validation is real.
- After `OutboxRelayIntegrationTest`, `select status from outbox` is `DELIVERED` and exactly one message was consumed
  from `mailer.commands`.
- A muted type produces zero outbox rows and one dispatch row with status `SUPPRESSED_MUTED`.
- `git diff --stat mailer/` is empty.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| The outbox relay's exchange mapping is declared wrong, so messages go to the default exchange | The integration test asserts the message arrives on the `mailer.commands` queue, not just that no exception was thrown |
| A second relay round republishes | `BrokerRelay` claims rows and marks after confirm; the test runs two rounds and asserts one message |
| `requestId` collides between two recipients | It is the dispatch id, which is unique per recipient; the test asserts two recipients produce two ids |
| Coalescing suppresses a genuinely important email | There is no coalescing window; the only dedupe is the unique `(tenant, source event, type, user)` key, which is scoped to one transition |
| A validator message carries the recipient address into `outbox.last_error` | The factory throws a fixed-message exception; the relay persists and logs that message, never the payload |
| The dispatch table becomes the second source of delivery truth | Status vocabulary stops at `PUBLISHED`; docs state mailer's ledger is authoritative |

## Security Considerations

- The command carries only the recipient, the subject and the bodies; no other user's data is attached, and variables
  are already escaped.
- `fromKey` is a configuration key, never an address; mailer resolves the address, so a tenant cannot make the suite
  send from an arbitrary domain.
- The relay runs only in the worker profile, so an `api` replica cannot publish commands.
- The broker user gets `write` on `mailer.commands` only; Phase 8's isolation assertions prove it cannot publish to
  `platform.events` or `billing.events`.
- The outbox payload is tenant-scoped by RLS like every other table; a bug that reads the wrong tenant sees nothing.

## Next Steps

Phase 7 exposes the read side: the user's notification list and read state, the preference and tenant settings APIs,
the template override API and the dispatch ledger query — each behind the app's audience, entitlement and permission
codes.
