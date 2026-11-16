---
phase: 3
title: "crm build skeleton, replica and gates"
status: pending
priority: P1
effort: "L"
dependencies: [2]
---

# Phase 3: crm Build Skeleton, Replica and Gates

## Goal

A `crm/` build that starts in all three runtimes, authenticates suite tokens, enforces the `crm` entitlement, replicates
Platform data, delivers its outbox to signed webhooks of Tenant Systems (endpoint management included) and fails the build on
any layering or isolation violation, with no business feature yet.

## Context

Copy the shape of `mailroom/` (committed and final): `settings.gradle.kts`, `build.gradle.kts`, `build-logic/`,
`apps/app-boot` (`config`, `health`, `infrastructure`, `jobs`, `replica`, `security`, `web`), `contexts/platform`,
`libs/{shared-core,jooq-schema}`, `testing/arch-fixtures`. Do not copy the skeleton from the in-flight `attribution/` or
`survey/`. Public paths: none (crm has no public surface).

crm owns no exchange. Its outbox feeds webhooks in process, the way Survey does: `OutboxRelay` (`core/core-messaging/.../
outbox/OutboxRelay.java`) publishes each due row as a Spring `EventEnvelope`, and the webhook context's listener records one
delivery per subscribed endpoint inside the relay's transaction (`survey/contexts/webhook/.../adapter/delivery/messaging/
WebhookEventListener.java`, `.../application/delivery/command/RecordDeliveriesUseCase.java`). The `webhook` context is the one
part copied from Survey: it is committed (`survey/contexts/webhook`, 43 files), and only its signing, destination policy,
DNS guard, sender and retry schedule live in `core/core-webhook`. Copy it, never import it, and never edit `survey/`. crm
wires no `BrokerRelay` and no `OutboxRoutes` assertion (`survey/.../config/OutboxConfiguration.java:30-60` asserts exports
for its broker relay, which crm does not have); its startup assertion is that the webhook listener bean exists. Messaging
config is consume-only, like mailroom's `platform.events` side.

## Files to create

- `crm/settings.gradle.kts` (`includeBuild("../core")`, `rootProject.name = "crm"`), `build.gradle.kts`, `build-logic/`,
  Gradle wrapper
- `crm/libs/shared-core` (ids, `CrmPermissions`), `crm/libs/jooq-schema` (generated, committed)
- `crm/contexts/{platform,customers,orders,activity,profile,webhook}` (layered modules; `platform` and `webhook` have code)
- `crm/apps/app-boot`: `Application`, `config/*` (Messaging, Outbox, Webhook, Replica, Scheduling, Suite, Settings, Web),
  `replica/*`, `health/ReplicaStateHealthContributor`, `web/*` (tenant context, entitlement filter, tenant-only guard,
  exception handler), `jobs/{TenantSweep,OutboxRelayJob,WebhookDeliveryJob,HousekeepingJob}`, `Dockerfile`,
  `application{,-api,-worker,-migrate}.yml`
- `crm/apps/app-boot/src/main/resources/db/migration/V1__baseline.sql`: roles `crm_owner` and `crm_app`, the RLS template,
  replica tables, `replica_state`, `event_inbox`, `outbox`, webhook endpoint and delivery tables (from Survey's migration),
  job lease and run tables, the reserved Platform tenant
- `crm/apps/app-boot/build.gradle.kts`: package `contracts/events/crm` into the jar like
  `mailroom/apps/app-boot/build.gradle.kts:69` (the webhook event-type catalog reads it), and the permission file for the
  contract test
- Tests: `RowLevelSecurityTest`, `JooqSchemaTest`, `ReplicaApplyTest`, `PermissionCatalogTest`, `OpenApiContractTest`,
  `ArchitectureTest`, `ArchitectureMetaTest`, `ModulithVerificationTest`, and the webhook tests copied with the context
  (endpoint management, signing, retry to `DEAD`, destination refusal)

## Tasks

1. Copy the mailroom skeleton, rename to `dev.ngb.crm`, delete every mailroom-specific class and table. No file under `crm/`
   may mention mailroom except a comment citing the precedent.
2. **Messaging.** Queue `crm.platform-events` bound to the five Platform state-transfer keys with the suite DLX pair. crm
   declares no exchange and has no export route. The outbox and `OutboxRelay` are wired; no publisher exists yet.
   **Webhooks.** Copy `survey/contexts/webhook`, rename to `dev.ngb.crm.webhook`, rename headers to `Crm-Event-Id`,
   `Crm-Event-Type`, `Crm-Timestamp`, `Crm-Key-Id`, `Crm-Signature`, `Crm-Attempt`, and point its event-type catalog at the
   four schemas in `contracts/events/crm/`. Endpoints and deliveries need `crm:webhook:manage`. The sender's
   `allowNonPublicTargets` (`core/core-webhook/.../WebhookHttpSender.java:47`) is a setting that is `false` by default and
   `true` only in the compose profile, so the smoke forwarder of phase 16 can receive deliveries.
3. **Replica.** `core-replica` and `core-platform-sync` unchanged, using the `crm` service client from phase 2.
4. **Security.** Suite token decoding from `core-suite`; the `crm` entitlement filter; platform-scope callers get `403` on every
   authenticated endpoint; the tenant comes from the token for a Tenant System and from the host for a user, as mailroom does.
5. **Settings.** Deployment settings (not per Tenant) with defaults: batch caps (1,000 customers or activities, 100 orders,
   5,000 lines per order batch), 500 lines per order, 8 KiB activity payload, export page size.
6. **Architecture gates.** Copy `ArchitectureRules`; add: `profile` reaches `customers`, `orders` and `activity` only through
   their `api` packages and writes no table; `orders` and `activity` reach `customers` only through its `api`; contexts never
   reach `platform` adapters; no business context imports `webhook` (it only listens to outbox events).
7. **Migration range.** Reserve `V10` for phase 5, `V20` for phase 6, `V30` for phase 7, `V40` for phase 8, `V50` for phase 9.

## Verification

- `cd crm && ./gradlew build` green: unit, ArchUnit, Modulith, RLS, jOOQ drift, replica apply, contract test.
- `bootRun` with each profile starts against the compose infrastructure; `api` readiness stays `DOWN` until the worker
  finishes one replica rebuild.
- A tenant-A token and a platform-scope token to a placeholder endpoint return `200` and `403`; a request with no token
  returns `401`.
- A test outbox row reaches a registered endpoint (a local receiver in the test) as a signed delivery that verifies with the
  endpoint's secret.
- `git grep -n -i "mailroom\|dev.ngb.mailroom\|survey\|dev.ngb.survey" crm/` reviewed by hand; `git grep -n "crm.events" crm/`
  finds nothing.

## Risks

- Copying carries mailroom decisions (campaign settings, tracking keys) and Survey decisions (its event names, its
  `billing.outbox.poll-delay` reference in `DeliverWebhooksUseCase`). Review every table, property and class.
- The jOOQ schema module is generated and committed: regenerate after each migration and commit it in the same change.
