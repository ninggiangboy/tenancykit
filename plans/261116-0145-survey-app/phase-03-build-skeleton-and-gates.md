---
phase: 3
title: "Build skeleton, replica and gates"
status: pending
priority: P1
effort: "L"
dependencies: [1]
---

# Phase 3: Build Skeleton, Replica and Gates

## Goal

A `survey/` build that starts in all three runtimes, authenticates suite tokens, enforces the `survey` entitlement,
replicates Platform data and fails the build on any layering or isolation violation, with no business feature yet.

## Context

Precondition: the mailroom rename is committed. Copy the shape of mailroom (formerly `email/`): `settings.gradle.kts`,
`build.gradle.kts`, `build-logic/`, `apps/app-boot` (`config`, `health`, `infrastructure`, `jobs`, `replica`,
`security`, `web`), `contexts/platform`, `libs/*`, `testing/arch-fixtures`, and the email plan's phase 3 checklist
(`plans/261115-1849-email-app/phase-03-build-skeleton-and-gates.md`).

## Files to create

- `survey/settings.gradle.kts` (`includeBuild("../core")`, `rootProject.name = "survey"`), `build.gradle.kts`,
  `build-logic/`, Gradle wrapper
- `survey/libs/shared-core` (ids, `SurveyPermissions`), `survey/libs/jooq-schema` (generated, committed)
- `survey/contexts/{platform,authoring,collection,insight,webhook}` (layered modules; only `platform` has code here)
- `survey/apps/app-boot`: `Application`, `config/*` (Messaging, Outbox, Replica, Scheduling, Suite, Security, Web),
  `replica/*`, `health/ReplicaStateHealthContributor`, `web/*` (tenant context, entitlement filter, tenant-only guard,
  exception handler), `jobs/TenantSweep`, `Dockerfile`, `application{,-api,-worker,-migrate}.yml`
- `survey/apps/app-boot/src/main/resources/db/migration/V1__baseline.sql`: roles `survey_owner` and `survey_app`, the RLS
  template, replica tables, `replica_state`, `event_inbox`, `outbox`, the reserved Platform tenant, `survey_settings`
- `survey/testing/arch-fixtures` and `ArchitectureRules`, `ArchitectureTest`, `ArchitectureMetaTest`,
  `ModulithVerificationTest`
- Tests: `RowLevelSecurityTest`, `JooqSchemaTest`, `ReplicaApplyTest`, `PermissionCatalogTest`, `OpenApiContractTest`

## Tasks

1. Copy the mailroom skeleton, rename to `dev.ngb.survey`, delete every mailroom-specific class. No file under
   `survey/` may mention mailroom except comments citing the precedent.
2. Messaging: exchange `survey.events` (exports configured, no publisher yet); queue `survey.platform-events` bound to
   the five Platform state-transfer keys with the suite DLX pair. The relay job is worker-only.
3. Replica: `core-replica` and `core-platform-sync` unchanged; a Platform service client `survey` with its own secret
   hash (configured in phase 10).
4. Security: suite token decoding from `core-suite`; the `survey` entitlement filter; platform-scope callers get `403`
   on every survey endpoint; the public prefix `/p/**` is excluded from the token and entitlement filters and gets
   its tenant from the host, as mailroom's `/t/**` does.
5. `survey_settings` (one row per tenant, lazy defaults): `default_locale`, `abandon_after` (24 h),
   `upload_max_bytes` (10 MiB), `public_rate_per_minute`.
6. Architecture gates: copy `ArchitectureRules`; add the rules that `collection` and `insight` reach `authoring` only
   through its `api` package, `insight` reaches `collection` only through its `api` package, and `insight`'s adapters
   write no table except `exports`.

## Verification

- `cd survey && ./gradlew build` green: unit, ArchUnit, Modulith, RLS, jOOQ drift, replica apply.
- `bootRun` with each profile starts; `api` readiness stays `DOWN` until the worker finishes one replica rebuild.
- A tenant-A token and a platform-scope token to a placeholder endpoint return `200` and `403`; a request to `/p/ping`
  with no token returns `200` with the host's tenant.
- `git grep -n -i "mailroom\|dev.ngb.mailroom" survey/` reviewed by hand.

## Risks

- Copying carries mailroom's decisions (tracking-token key names, campaign settings). Review every table and property.
- The jOOQ schema module is generated and committed: regenerate after each migration and commit it in the same change.
