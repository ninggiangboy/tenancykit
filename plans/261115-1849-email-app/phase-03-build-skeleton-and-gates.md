---
phase: 3
title: "Build skeleton, replica and gates"
status: done
priority: P1
effort: "L"
dependencies: [1]
---

# Phase 3: Build Skeleton, Replica and Gates

## Goal

An `email/` build that starts in all three runtimes, authenticates suite tokens, enforces the `email` entitlement,
replicates Platform data, and fails the build on any layering or isolation violation — with no business feature yet.

## Context

Copy the shape of `notification/` (the commit `b3472cbf` lists every file a new service needs):
`notification/{build.gradle.kts,settings.gradle.kts,build-logic,apps/app-boot,contexts/platform,libs/*,testing/arch-fixtures}`,
the `boot/replica`, `boot/security`, `boot/web`, `boot/jobs` packages, `docs/notification.md`, and
`plans/261115-0619-notification-service/phase-02` and `phase-03`.

## Files to create

- `email/settings.gradle.kts` (`includeBuild("../core")`, `rootProject.name = "email"`), `build.gradle.kts`, `build-logic/`
- `email/libs/shared-core` (ids, `TemplateKey`-like value objects, `EmailPermissions`), `email/libs/jooq-schema` (generated, committed)
- `email/contexts/platform` (replica ports, adapters, application), `content`, `audience`, `sending` (empty layered modules)
- `email/apps/app-boot`: `Application`, `config/*` (Messaging, Outbox, Replica, Scheduling, Suite, Security, Web),
  `replica/*`, `health/ReplicaStateHealthContributor`, `web/*` (entitlement and scope filters), `jobs/TenantSweep`,
  `Dockerfile`, `application{,-api,-worker,-migrate}.yml`
- `email/apps/app-boot/src/main/resources/db/migration/V1__baseline.sql` (roles `email_owner`/`email_app`, RLS template,
  replica tables, `replica_state`, `event_inbox`, `outbox`, reserved Platform tenant, `email_settings`)
- `email/testing/arch-fixtures` and the architecture tests (`ArchitectureRules`, `ArchitectureTest`, `ArchitectureMetaTest`, `ModulithVerificationTest`)
- Tests: `RowLevelSecurityTest`, `JooqSchemaTest`, `ReplicaApplyTest`, `PermissionCatalogTest`, `OpenApiContractTest`

## Tasks

1. Scaffold the build by copying `notification/` and renaming; delete everything dispatch-specific. Package root
   `dev.ngb.email`; no class name or comment may mention notification.
2. Messaging: `Document(2)`; queues `email.platform-events` (the five state-transfer keys) and `email.mailer-events`
   (bound to `mailer.events`, key `email-delivery.changed`), each with the suite DLX pair; exports
   `{"email.send": "mailer.commands"}`. The relay job is worker-only.
3. Replica: reuse `core-replica` and `core-platform-sync` unchanged; configure a Platform service client named `email`
   with its own secret hash (see `docs/notification.md`, "Replica rebuild").
4. Security: suite token decoding from `core-suite`; the `email` entitlement filter on HTTP only; the guard rule from
   notification — platform-scope callers get `403` on every email endpoint, because all email data is tenant data.
5. `email_settings` (one row per tenant): default `from_key`, `from_name`, `reply_to`, `body_retention_days` (30),
   `campaign_publish_per_minute`. Defaults are created lazily; a missing row means the configured defaults.
6. Architecture gates: copy `ArchitectureRules`, adapt package names, and keep `ArchitectureMetaTest` so the rules are
   proven to fail on the fixtures. Add the rule that `sending` reaches `content` and `audience` only through `api`.
7. Compose and ops wiring are phase 10; here add only what is needed to run the tests (Postgres and Rabbit containers).

## Verification

- `cd email && ./gradlew build` is green: unit, ArchUnit, Modulith verification, RLS, jOOQ schema drift, replica apply.
- `bootRun` with each profile starts; `api` readiness stays `DOWN` until the worker finishes one replica rebuild.
- A request with a tenant-A token and a platform-scope token to a placeholder endpoint returns `200` and `403`.
- No file under `email/` contains the string `notification` outside licence-free comments that cite the precedent
  (`git grep -n -i notification email/` reviewed by hand).

## Risks

- Copying silently carries notification's decisions (shared templates without `tenant_id`). Review every table.
- The jOOQ schema module is generated and committed: regenerate after each migration and commit it in the same change.
