---
phase: 4
title: "loyalty build skeleton, replica and gates"
status: done
priority: P1
effort: "L"
dependencies: [2]
---

# Phase 4: loyalty Build Skeleton, Replica and Gates

## Goal

A `loyalty/` build that starts in all three runtimes, authenticates suite tokens, enforces the `loyalty` entitlement,
replicates Platform data and fails the build on any layering or isolation violation, with no business feature yet. It knows
nothing of crm: every order reaches it through its own API, called by the Tenant's system.

## Context

Same copy as phase 3 (`mailroom/` is the template). loyalty consumes only `platform.events` and binds to no other app's
exchange. Its outbox feeds signed webhooks in process, exactly as crm's does (phase 3): the `webhook` context is copied from
`survey/contexts/webhook`, `OutboxRelay` is wired and no `BrokerRelay` or exchange exists. Phases 11 to 16 write the events. A Tenant
without the `loyalty` entitlement is refused by the entitlement filter on every endpoint, so no event filtering is needed. It
runs in parallel with phase 3 (disjoint trees).

## Files to create

- `loyalty/settings.gradle.kts`, `build.gradle.kts`, `build-logic/`, Gradle wrapper
- `loyalty/libs/{shared-core,jooq-schema}`
- `loyalty/contexts/{platform,program,points,tiers,rewards,webhook}` (only `platform` and `webhook` have code)
- `loyalty/apps/app-boot`: `Application`, `config/*` (Messaging, Outbox, Webhook, Replica, Scheduling, Suite, Settings, Web),
  `replica/*`, `health/*`, `web/*`, `jobs/{TenantSweep,OutboxRelayJob,WebhookDeliveryJob,HousekeepingJob}`, `Dockerfile`,
  `application{,-api,-worker,-migrate}.yml`
- `loyalty/apps/app-boot/src/main/resources/db/migration/V1__baseline.sql` (roles, RLS template, replica tables, inbox, outbox,
  webhook endpoint and delivery tables, job tables)
- Tests as in phase 3, including the webhook tests copied with the context

## Tasks

1. Copy the mailroom skeleton, rename to `dev.ngb.loyalty`, delete every mailroom-specific class and table.
2. **Messaging.** Queue `loyalty.platform-events` (five Platform keys) with the suite DLX pair, and nothing else. No exchange
   is declared and no `BrokerRelay` is wired.
   **Webhooks.** As phase 3, with package `dev.ngb.loyalty.webhook`, headers `Loyalty-Event-Id`, `Loyalty-Event-Type`,
   `Loyalty-Timestamp`, `Loyalty-Key-Id`, `Loyalty-Signature`, `Loyalty-Attempt`, the event-type catalog read from the
   schemas in `contracts/events/loyalty/` (packaged by the build, no change in `core/`), `loyalty:webhook:manage` on
   endpoints and deliveries, and `allowNonPublicTargets` `false` except in the compose profile.
3. **Replica and security.** As phase 3, with the `loyalty` service client and entitlement.
4. **Settings.** Deployment settings: the expiry and tier-evaluation job batch sizes and schedules, the order line cap
   (default 500, the same default as crm's).
5. **Architecture gates.** Copy `ArchitectureRules`; add: `points` reaches `program` only through its `api`; `tiers` and
   `rewards` reach `points` only through its `api`; `program` reaches no other context; no class under `loyalty/` names a
   crm type, package or exchange (an ArchUnit rule on package names plus the grep below).
6. **Migration range.** Reserve `V10` for phase 10, `V20` for phase 11, `V30` for phase 12, `V40` for phase 13, `V50` for
   phase 14, `V60` for phase 15, `V70` for phase 16.

## Verification

- `cd loyalty && ./gradlew build` green with no crm container or schema anywhere on the classpath.
- `bootRun` in each profile starts against compose infrastructure that does not run crm.
- A tenant token and a platform-scope token get `200` and `403` on a placeholder endpoint; a token of a Tenant without the
  `loyalty` entitlement gets `403`.
- `git grep -n -i "mailroom\|dev.ngb.mailroom" loyalty/` reviewed by hand; `git grep -n -i "crm" -- loyalty/apps
  loyalty/contexts loyalty/libs` finds nothing.

## Risks

- Copying carries mailroom decisions; review every table, property and class. The webhook copy carries Survey names:
  `git grep -n -i survey loyalty/` finds nothing.
- The jOOQ schema module is generated and committed: regenerate after each migration and commit it in the same change.
