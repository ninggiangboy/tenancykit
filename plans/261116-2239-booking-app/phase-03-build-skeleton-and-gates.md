---
phase: 3
title: "Build skeleton, replica and gates"
status: completed
priority: P1
effort: "L"
dependencies: [1, 2]
---

# Phase 3: Build Skeleton, Replica and Gates

## Goal

`booking/` builds, migrates, starts as api and worker, consumes Platform's replica, serves an authenticated empty API with
tenant isolation, and fails its build on any dependency on another service.

## Context

Copy the committed skeleton of `survey/` (`apps/app-boot`, `contexts/*`, `libs/{shared-core,jooq-schema}`, `testing/arch-fixtures`,
`build-logic`), not the in-flight attribution tree. Its `platform` context is the replica, `TenantSweep` runs per-tenant jobs
under RLS, and `SecurityConfiguration` shows the two principals. Prefix `/booking/api/v1`, package `dev.ngb.booking`,
audience and entitlement `booking`, queue `booking.platform-events`. The exclusion constraint needs the `btree_gist`
extension; migrations create extensions with `create extension if not exists` (survey `V7`).

## Files to create

- `booking/{settings.gradle.kts,build.gradle.kts,gradlew,gradle/**,build-logic/**}`
- `booking/apps/app-boot/**`: `Application`, configuration for replica, security, tenancy, settings, jobs (`TenantSweep`),
  `application.yml`, migrations `V1__baseline.sql` (replica tables, outbox, inbox, idempotency, job leases, `btree_gist`)
- `booking/contexts/{platform,catalog,inventory,booking,gate,webhook}/build.gradle.kts` and empty layered source sets
- `booking/libs/{shared-core,jooq-schema}`: value objects (ids, `Instant` helpers, `Zone`), generated jOOQ schema committed
- `booking/testing/arch-fixtures/**`, `booking/apps/app-boot/src/test/**/ArchitectureRules.java`
- `booking/README.md`

## Tasks

1. Copy and rename; remove everything survey-specific (surveys, links, visits, GeoIP, MinIO). Keep: replica consumer,
   snapshot sync, tenant filter, entitlement filter, `TenantSweep`, outbox, idempotency, list query wiring, health.
2. Context map and allowed edges, enforced by ArchUnit: `catalog` ← `inventory` ← `booking` ← `gate`; `webhook` reads
   `booking` events only through the outbox; `platform` depends on nothing; every cross-context call goes through the other
   context's `api` package. Add the shared `core-arch` rule set and the isolation Gradle task that fails on another service's
   dependency.
3. Migration `V1`: replica tables as survey, then `create extension if not exists btree_gist`. Prove the owner role can create it
   on the compose database in this phase, before any engine code; if it cannot, stop and record the fix.
4. Settings (`BookingSettings`): hold TTL, max extension, grace, offer window, horizon days, token lifetime cap, rate limit,
   admission cap, `jobs.tenantParallelism`. Defaults from plan.md Key decisions.
5. Runtime profiles `api`, `worker`, `migrate` as survey; the worker owns every `@Scheduled` job.

## Verification

- `cd booking && ./gradlew check` is green, including ArchUnit, Modulith and the isolation gate; `testing/arch-fixtures` proves each
  rule fails on a deliberately bad class.
- `docker compose up -d --build --wait` brings up `booking-migrate`, `booking-api`, `booking-worker`; `/actuator/health` is UP
  and the replica completion marker is written.
- A token for tenant A against tenant B's host is refused; an entitlement-less tenant gets the standard `403`.
- `git grep -n -i -E "survey|mailroom|attribution|crm|loyalty" -- booking` finds nothing outside comments that cite the copied
  pattern (and those are removed).

## Risks

- Copying leaves survey names behind: the grep above is part of the gate.
- Generated jOOQ code drifts: regenerate and commit with the migration, as every app does.
