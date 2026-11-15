---
title: "Phase 5: Notification Catalog, Transitions and In-App Store"
status: todo
---

# Phase 5: Notification Catalog, Transitions and In-App Store

## Context Links

- `contracts/events/platform/{user-access,tenant,entitlement}.changed.schema.json` — the three payloads this phase reads
- `core/core-replica/src/main/resources/dev/ngb/core/replica/replica-tables.sql` — `user_grants` columns
- `contracts/permissions/platform.yaml:38-41` — `platform:user:manage`, the tenant-admin audience proxy
- `billing/apps/app-boot/src/main/resources/db/migration/V2__delivery.sql` — the RLS and constraint style to copy
- Phase 3's `UserDirectory`/`TenantDirectory` and the replica handler

## Overview

Priority P1, size L. The first phase that produces notifications. A Platform event arrives, the replica is upserted,
and a fixed catalog decides whether the transition deserves a notification, who receives it and which variables the
message carries. The in-app row is written and rendered in the same transaction as the replica update, so a notification
either exists with the state that caused it or does not exist at all.

Email is not published yet; that is Phase 6. This phase ends with a stored, readable, rendered in-app notification and a
truth table of tests proving which transitions do and do not notify.

## Key Insights

- **State-transfer events do not say "this changed".** They carry the full current row. Every rule therefore compares a
  previous snapshot with a current one, and the previous row must be read **before** the upsert inside the same
  transaction. Reading it afterwards yields the new state twice and every rule silently stops firing.
- Permission recomputation is the noisy path: one role edit emits `user-access.changed` for every affected user, and a
  recompute with identical permissions emits one too. `ACCESS_CHANGED` must therefore fire only on an actual set
  difference, which is also what makes the message worth sending.
- `user-access.changed` covers platform-scope users as well. Every rule filters `scope = TENANT`; otherwise Platform's
  own administrators receive tenant notifications.
- The tenant-admin audience has no dedicated permission. `platform:user:manage` (tenant scope) is the closest existing
  proxy for "manages this tenant"; introducing a new Platform permission would change another service for a read-only
  audience rule.
- A user that just became `DISABLED` still gets an in-app row (they may be re-enabled and read it) but will not get an
  email in Phase 6 — the two channels have different capability and this is the first place that shows.
- Two dedupe layers are needed and neither is redundant: the inbox stops a redelivered envelope, and a unique
  `(tenant, source event, user, type)` stops a *different* envelope describing the same transition (a retried
  publication with a new id).
- Tenant settings decide whether a type exists for that tenant at all; an absent settings row means all five are
  enabled with locale `en`, so a tenant that never configured anything still gets notifications.

## Requirements

Functional

- Five rules with explicit match predicates:

  | Type | Fires when | Audience |
  |---|---|---|
  | `ACCESS_GRANTED` | no previous row, current `ACTIVE`, `scope = TENANT` | the subject user |
  | `ACCESS_CHANGED` | previous and current differ in `permissions`, current `ACTIVE`, `scope = TENANT` | the subject user |
  | `ACCESS_DISABLED` | previous `ACTIVE`, current `DISABLED`, `scope = TENANT` | the subject user |
  | `ENTITLEMENT_CHANGED` | `enabled` differs from the previous entitlement row, or the row is new | tenant users holding `platform:user:manage`, `scope = TENANT`, `ACTIVE` |
  | `TENANT_DEACTIVATED` | previous tenant `ACTIVE`, current `DEACTIVATED` | every `ACTIVE` tenant-scope user of that tenant |

- Variables per type are a plain map: tenant name and code, the subject's display name, added and removed permission
  codes, the app code and its new enabled state. No personal data beyond a display name and the caller's own email.
- A disabled type in tenant settings produces no notification and no row.
- In-app rows carry the rendered title and body, the resolved locale, the template key and version, the source event id
  and type.
- Replaying an event id, or a second envelope describing the same transition, writes nothing twice.

Non-functional

- One unit of work per event: replica upsert, previous read, in-app insert and (Phase 6) outbox row commit together.
- Rule evaluation is a pure function of two snapshots and the tenant's settings; no repository call inside a predicate.
- Every rule has a name that appears in the dispatch ledger's `notification_type`.

## Architecture

```
InboundEventHandler (boot, worker/api)
  unit of work + event_inbox dedupe (core-messaging dispatcher)
  ├─ previous := UserDirectory/…        (read before the write)
  ├─ ReplicaWriter.upsert(stream, doc)  (version guard)
  └─ AcceptChangeUseCase.accept(...)    (opens/joins the unit of work)
         ├─ NotificationCatalog.resolve(previous, current) → type | none
         ├─ settings: enabled types + default locale
         ├─ audience → recipients from the replica
         ├─ TemplateRenderer.render(key, LocalePolicy.choose(userPref, tenantDefault), variables)
         └─ NotificationRepository.insertIfAbsent(...)   (unique key = the second dedupe layer)
```

Catalog classes live in `application` and import the `platform` context's `api` views; they do not live in `domain`,
because a rule needs two cross-context snapshots and the domain must stay free of them.

## Related Code Files

Create

- `notification/contexts/dispatch/src/domain/java/dev/ngb/notification/dispatch/domain/notification/{Notification,NotificationId,NotificationStatus}.java`
- `notification/contexts/dispatch/src/application/java/dev/ngb/notification/dispatch/application/notification/command/AcceptChangeUseCase.java`
- `notification/contexts/dispatch/src/application/java/dev/ngb/notification/dispatch/application/notification/service/catalog/{NotificationRule,AccessGrantedRule,AccessChangedRule,AccessDisabledRule,EntitlementChangedRule,TenantDeactivatedRule,NotificationCatalog}.java`
- `notification/contexts/dispatch/src/application/java/dev/ngb/notification/dispatch/application/notification/service/{AudienceResolver,NotificationSettingsView}.java`
- `notification/contexts/dispatch/src/application/java/dev/ngb/notification/dispatch/application/notification/port/{NotificationRepository,NotificationSettingsRepository,NotificationPreferenceRepository,NotificationTenantDirectory}.java`
- `notification/contexts/dispatch/src/adapter/java/dev/ngb/notification/dispatch/adapter/notification/persistence/{JooqNotificationRepository,JooqNotificationSettingsRepository,JooqNotificationPreferenceRepository}.java`
- `notification/apps/app-boot/src/main/resources/db/migration/V3__notifications.sql`
- `notification/apps/app-boot/src/test/java/dev/ngb/notification/dispatch/{NotificationCatalogTest,AcceptChangeUseCaseTest,NotificationDedupeIntegrationTest}.java`

Modify

- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/replica/PlatformEventsHandler.java` — call the use
  case after the upsert
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/config/ReplicaConfiguration.java` — wire the
  catalog and its rules
- `notification/apps/app-boot/src/test/java/dev/ngb/notification/architecture/ArchitectureRules.java` — add the new
  tables to `TABLE_OWNER` under `dispatch`
- `notification/libs/jooq-schema` — regenerated

## Implementation Steps

1. Write `Notification` (id, tenant, user, type, template key, locale, title, body, link, readAt, createdAt) with
   `markRead(Instant)` and no setters; `NotificationId` is a typed id beside it.
2. Write `V3__notifications.sql`:

   ```sql
   create table notifications (
       id uuid primary key, tenant_id uuid not null, user_id uuid not null,
       type text not null, source_event_id uuid not null, source_event_type text not null,
       template_key text not null, template_version integer not null, locale text not null,
       title text not null, body text not null, link text,
       read_at timestamptz, created_at timestamptz not null,
       unique (tenant_id, source_event_id, user_id, type));
   create index notifications_inbox_idx on notifications (tenant_id, user_id, created_at desc, id);
   call enable_tenant_rls('notifications');

   create table notification_settings (
       tenant_id uuid primary key, default_locale text not null default 'en',
       enabled_types text[] not null, updated_at timestamptz not null default now());
   call enable_tenant_rls('notification_settings');

   create table notification_preferences (
       tenant_id uuid not null, user_id uuid not null, locale text,
       muted_types text[] not null default '{}', updated_at timestamptz not null default now(),
       primary key (tenant_id, user_id));
   call enable_tenant_rls('notification_preferences');
   ```

3. Write the five rules. Each exposes `type()`, `templateKey()` (from Phase 2's enum mapping), `matches(previous,
   current)` and `variables(previous, current, tenant)`. `AudienceResolver` resolves the recipient list: self, or
   `usersWithPermission(tenantId, "platform:user:manage")`, or `activeUsersOf(tenantId)`.
4. Write `NotificationCatalog` as the ordered list of rules with `Optional<ResolvedNotification> resolve(eventType,
   previous, current, tenant)`; the first matching rule wins and the order is asserted by a test.
5. Write `AcceptChangeUseCase.accept(envelopeId, eventType, tenantId, previous, current)`. It opens the unit of work
   (the architecture rule requires it), reads settings and preferences, renders per recipient, and inserts with
   `on conflict do nothing`, returning the accepted and skipped counts for logging.
6. Wire the replica handler: read the previous view through the `platform` ports, upsert, then call the use case. Keep
   the previous read inside the dispatcher's transaction.
7. Map `document type → rule` for the five event types; an unknown type is ignored silently (the queue is bound to
   exactly five routing keys, so an unknown type means a producer mistake worth no notification but worth a debug log).
8. Tests:
   - `NotificationCatalogTest`: the truth table above, plus "identical permissions → none", "platform scope → none",
     "new entitlement row → fires", "same enabled state → none".
   - `AcceptChangeUseCaseTest`: a disabled type produces no row; a user preference locale wins over the tenant default;
     an owner-less tenant settings row defaults to all five types.
   - `NotificationDedupeIntegrationTest`: same envelope twice → one row; a second envelope with the same transition →
     still one row.

## Todo

- [ ] `Notification` aggregate and typed id
- [ ] `V3__notifications.sql` with the three tables and RLS
- [ ] Five rules plus `AudienceResolver`
- [ ] `NotificationCatalog` with asserted ordering
- [ ] `AcceptChangeUseCase` opening the unit of work
- [ ] Replica handler calls the use case after the upsert
- [ ] Catalog, use-case and dedupe tests

## Success Criteria

- `cd notification && ./gradlew :apps:app-boot:test --tests '*NotificationCatalog*' --tests '*AcceptChange*'
  --tests '*NotificationDedupe*'` passes.
- The catalog truth table covers all five types plus three negative cases, and the test fails if two rules match the
  same transition.
- An integration test inserts the same `(tenant, source_event_id, user, type)` twice and `select count(*)` is 1.
- `psql -c '\d notifications'` as `notification_app` shows the unique constraint and RLS enabled.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| The previous row is read after the upsert, so no rule ever fires | The handler reads first; a test asserts a real transition produces a row, which cannot pass in the wrong order |
| Permission-set comparison uses array identity, producing a false change every time | Compare as sorted sets; the test asserts an unchanged array produces no notification |
| Tenant-admin audience is huge (every user holds `platform:user:manage` in small tenants) | Accepted: the audience is explicit, bounded by the tenant's own administrators, and every recipient produces one dispatch row |
| Nested unit of work from the use case conflicts with the dispatcher's transaction | `SpringUnitOfWork` joins an existing transaction; the test asserts one row per event under the listener, not only through a direct call |
| A tenant-deactivation event arrives while its users are being disabled | Both notifications may fire; they are different types with different audiences, and the dedupe key keeps each to one row |

## Security Considerations

- Every query takes a tenant id and filters on it; RLS on all three tables is the second line, verified by a test that
  runs as `notification_app`.
- No personal data is copied into the notification: the body uses the display name, and the recipient address stays in
  the replica and in the dispatch row (Phase 6), never in the message variables.
- Variables are escaped by the renderer, so a permission code or tenant name cannot inject markup.
- A user can only ever read their own rows; the ownership filter is part of the query, not of the controller.

## Next Steps

Phase 6 adds the email half: the outbox row, the `email.send` payload built from the same rendered message, the
suppression window, and the dispatch ledger that answers "why was no email sent to this user".
