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

An `attribution/` build that starts in all three runtimes, authenticates suite tokens, enforces the `attribution`
entitlement, replicates Platform data and fails the build on any layering or isolation violation, with no business
feature yet.

## Context

Copy the shape of `mailroom/`: `settings.gradle.kts`, `build.gradle.kts`, `build-logic/`, `apps/app-boot` (`config`,
`health`, `infrastructure`, `jobs`, `replica`, `security`, `web`), `contexts/platform`, `libs/{shared-core,jooq-schema}`,
`testing/arch-fixtures`, and the checklist of `plans/261115-1849-email-app/phase-03-build-skeleton-and-gates.md`. Public
prefixes follow `mailroom/.../security/SecurityConfiguration.java:47` and the two filters that skip them
(`AppEntitlementFilter`, `TenantContextFilter`).

## Files to create

- `attribution/settings.gradle.kts` (`includeBuild("../core")`, `rootProject.name = "attribution"`), `build.gradle.kts`,
  `build-logic/`, Gradle wrapper
- `attribution/libs/shared-core` (ids, `AttributionPermissions`), `attribution/libs/jooq-schema` (generated, committed;
  the code generator excludes partition tables by name pattern)
- `attribution/contexts/{platform,links,tracking,attribution,spend,insight}` (layered modules; only `platform` has code)
- `attribution/apps/app-boot`: `Application`, `config/*` (Messaging, Outbox, Replica, Scheduling, Suite, Security, Web,
  Secrets), `replica/*`, `health/ReplicaStateHealthContributor`, `web/*` (tenant context, entitlement filter, tenant-only
  guard, exception handler), `jobs/TenantSweep`, `Dockerfile`, `application{,-api,-worker,-migrate}.yml`
- `attribution/apps/app-boot/src/main/resources/db/migration/V1__baseline.sql`: roles `attribution_owner` and
  `attribution_app`, the RLS template, replica tables, `replica_state`, `event_inbox`, `outbox`, the reserved Platform
  tenant, `attribution_settings`
- Tests: `RowLevelSecurityTest`, `JooqSchemaTest`, `ReplicaApplyTest`, `PermissionCatalogTest`, `OpenApiContractTest`,
  `ArchitectureTest`, `ArchitectureMetaTest`, `ModulithVerificationTest`

## Tasks

1. Copy the mailroom skeleton, rename to `dev.ngb.attribution`, delete every mailroom-specific class. No file under
   `attribution/` may mention mailroom except comments citing the precedent.
2. **Messaging.** Queue `attribution.platform-events` bound to the five Platform state-transfer keys with the suite DLX
   pair. The outbox and its relay are built because the baseline copies them, but no publisher exists: the app declares
   no `*.events` exchange.
3. **Replica.** `core-replica` and `core-platform-sync` unchanged; a Platform service client `attribution` with its own
   secret hash (configured in phase 12).
4. **Security.** Suite token decoding from `core-suite`; the `attribution` entitlement filter; platform-scope callers get
   `403` on every authenticated endpoint. Public, token-free prefixes: `/go/**`, `/t/**`, `/attribution/t/**`; tenant
   from the host, except the one OAuth callback in phase 11 which reads it from a signed `state`.
5. **Settings.** `attribution_settings` (one row per tenant, lazy defaults): `click_param` (`aid`), `allowed_origins`
   (empty list), `default_window_days` (30), `public_rate_per_minute`. Retention is deployment-wide configuration, not a
   Tenant setting.
6. **Secrets and keys.** Configuration `ATTRIBUTION_CLICK_KEY_ID` / `ATTRIBUTION_CLICK_SECRET` (click token HMAC, key
   rotation like mailroom's tracking keys), `ATTRIBUTION_VISITOR_HASH_KEY`, `ATTRIBUTION_SECRETS_MASTER_KEY` and
   `_ID` for `AesGcmSecretCipher`, `ATTRIBUTION_PUBLIC_BASE_URL`, `ATTRIBUTION_GEOIP_PATH`. The app refuses to start when
   a key is empty, as `TrackingConfiguration` does in mailroom.
7. **Architecture gates.** Copy `ArchitectureRules`; add: `tracking`, `attribution` and `insight` reach `links` only
   through its `api` package; `attribution` reaches `tracking` only through its `api` package; `insight`'s adapters
   write no table except rollups; `spend` reaches no other context but `platform`.

## Verification

- `cd attribution && ./gradlew build` green: unit, ArchUnit, Modulith, RLS, jOOQ drift, replica apply.
- `bootRun` with each profile starts; `api` readiness stays `DOWN` until the worker finishes one replica rebuild.
- A tenant-A token and a platform-scope token to a placeholder endpoint return `200` and `403`; a request to `/go/x`,
  `/t/v1/ping` with no token reaches the app and gets the host's tenant (404 for an unknown slug, never 401).
- `git grep -n -i "mailroom\|dev.ngb.mailroom" attribution/` reviewed by hand.

## Risks

- Copying carries mailroom decisions (tracking key names, campaign settings). Review every table and property.
- The jOOQ schema module is generated and committed: regenerate after each migration and commit it in the same change.
