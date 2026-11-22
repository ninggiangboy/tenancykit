---
phase: 7
title: "Notification producer"
status: pending
priority: P1
effort: "1d"
dependencies: [2, 6]
---

# Phase 7: Notification producer

## Goal

When Notification stores an in-app notification for a user, it also sends `notification.created` to that user through
realtime, carrying the new unread count, in the same transaction.

## Context

- `backend/services/infra/notification/` already has a `BrokerRelay` (exports `email.send` to `mailer.commands`), the
  outbox, a worker-only relay job and `MessagingHousekeeping`.
- The in-app row is stored for every type, including for a muted user and a disabled account (docs/notification.md), so
  the signal fires for every stored row; a disabled user simply has no connection.
- Notification has no domain event to mirror for this fact, so it uses the SDK's direct API (see phase 2), not the
  mapping decorator.

## Requirements

1. The dispatch use case that inserts the in-app row also calls the SDK to append a broker-only `realtime.push` row:
   `signal = notification.created`, `app = notification`, `audience = {kind:user, userId}`,
   `ref = {type:notification, id}`, `data = {unread: <count after insert>}`.
2. `MessagingProperties.exports` gains `realtime.push -> realtime.commands`; the startup assertion keeps working.
3. `ops/rabbitmq/declare-users.sh`: the `notification` user gains `write` on `realtime.commands` and nothing else new;
   `ops/smoke/isolation.sh` gets the matching positive and negative assertions (finalized in phase 13).
4. Housekeeping never deletes a broker-only row before the broker cursor finished.
5. Unread count is computed in the same transaction as the insert, so concurrent inserts cannot publish a stale total
   in a way the badge shows as lower after a higher one; the client takes the latest value it receives by id order.

## Files

Modify under `backend/services/infra/notification/`:
- the dispatch application service that stores the in-app row (find with
  `grep -rn "inapp" contexts/dispatch/src --include=*.java`)
- `apps/app-boot/.../config/MessagingConfiguration.java` (export entry)
- `apps/app-boot/.../jobs/MessagingHousekeeping.java` (predicate)
- `build.gradle.kts` of `contexts:dispatch` or `apps:app-boot` (dependency on `core-realtime`)
- tests under `apps/app-boot/src/test/.../dispatch/`

Also modify: `ops/rabbitmq/declare-users.sh`.

## Steps

1. Read the dispatch flow end to end and mark the single place where the in-app row is inserted and the dedupe keys are
   applied; the signal must only be written when a row was actually inserted (not on a deduplicated envelope).
2. Add the SDK dependency and the call, with the unread count query in the same transaction.
3. Add the export entry and run the existing `EmailCommandContractTest` to confirm email commands are unaffected.
4. Add tests: a grant event produces one in-app row, one email command row and one `realtime.push` row with the right
   audience and `unread`; a redelivered envelope produces none; a muted user still gets the signal.
5. Housekeeping test: a broker-only row with `publish_status = PENDING` survives the sweep, a published expired one is
   deleted.
6. Update `declare-users.sh`.

## Todo

- [ ] Signal written with the in-app insert (and only then)
- [ ] Export entry for `realtime.push`
- [ ] Housekeeping predicate and test
- [ ] Dispatch tests for grant, redelivery and muted user
- [ ] Broker grant added

## Verification

- `cd backend/services/infra/notification && ./gradlew build` passes.
- The new dispatch tests pass and the existing `EmailCommandContractTest` and `NotificationCatalogTest` are unchanged.

## Risks

- Writing the signal when the row insert was deduplicated would announce nothing new; step 1 and its test prevent it.
- The unread count query adds a read to the hot path; it is an indexed count per user, measured in phase 13.

## Security

- The signal names only the recipient user and a notification id; the title and body stay behind the REST inbox, which
  applies the user's own scope and RLS.

## Next

Phase 10.
