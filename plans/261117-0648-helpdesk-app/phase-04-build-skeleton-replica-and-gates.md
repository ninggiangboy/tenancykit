---
phase: 4
title: "Build skeleton, replica and gates"
status: pending
priority: P1
effort: "L"
dependencies: [1, 3]
---

# Phase 4: Build Skeleton, Replica and Gates

## Goal

`helpdesk/` builds, migrates, starts as api and worker, consumes Platform's replica, serves an authenticated empty API with
tenant isolation, and fails its build on any dependency on another service.

## Context

Copy the committed skeleton of `booking/` (`apps/app-boot`, `contexts/*`, `libs/{shared-core,jooq-schema}`,
`testing/arch-fixtures`, `build-logic`); survey and booking are the two committed references and `booking/contexts/webhook`
is the nearest webhook context. Prefix `/helpdesk/api/v1`, package `dev.ngb.helpdesk`, audience and entitlement `helpdesk`,
queue `helpdesk.platform-events`. The replica includes `user_grants` (agents), `tenant_domains` (host resolution) and
`app_entitlements`.

## Files to create

- `helpdesk/{settings.gradle.kts,build.gradle.kts,gradlew,gradle/**,build-logic/**}`
- `helpdesk/apps/app-boot/**`: `Application`, configuration for replica, security, tenancy, settings, jobs (`TenantSweep`),
  `application.yml`, migration `V1__baseline.sql` (replica tables, outbox, inbox, idempotency, job leases)
- `helpdesk/contexts/{platform,ticketing,sla,channel,mail,webhook}/build.gradle.kts` and empty layered source sets
- `helpdesk/libs/{shared-core,jooq-schema}`: ids, time helpers, generated jOOQ schema committed
- `helpdesk/testing/arch-fixtures/**`, `helpdesk/apps/app-boot/src/test/**/ArchitectureRules.java`, `helpdesk/README.md`

## Context map (enforced by ArchUnit)

`platform` depends on nothing. `ticketing` (tickets, messages, queues, agents, attachments, canned replies, merge) depends on
`platform`. `sla` and `channel` (channels, forms, domains, inbound intake) and `mail` (outbound mail, delivery results,
thread tokens) depend on `ticketing`'s `api` package only. `webhook` reads domain events through the outbox, never from a
table of another context. Every cross-context call goes through the other context's `api` package.

## Tasks

1. Copy and rename; remove everything booking-specific. Keep: replica consumer, snapshot sync, tenant filter, entitlement
   filter, `TenantSweep`, outbox, idempotency, list query wiring, health, the public-guard filter shape.
2. Add the shared `core-arch` rule set and the isolation Gradle task that fails on another service's dependency.
3. Settings class `HelpdeskSettings` with defaults from `plan.md` (auto-close days 7, at-risk fraction 0.8, attachment limits,
   public rate limit, form daily cap, token lifetimes, inbound replay window, `jobs.tenantParallelism`).
4. Runtime profiles `api`, `worker`, `migrate` as booking; the worker owns every `@Scheduled` job.
5. Wire the `mailer.events` consumer queue declaration (`helpdesk.mailer-events` with DLQ) and the `email.send` producer port as
   empty seams; phase 12 fills them.

## Verification

- `cd helpdesk && ./gradlew check` is green including ArchUnit, Modulith and the isolation gate; `testing/arch-fixtures`
  proves each rule fails on a deliberately bad class.
- `docker compose up -d --build --wait` brings up `helpdesk-migrate`, `helpdesk-api`, `helpdesk-worker`; `/actuator/health` is
  UP and the replica completion marker is written.
- A token for tenant A against tenant B's host is refused; an entitlement-less tenant gets the standard `403`.
- `git grep -n -i -E "survey|mailroom|attribution|customer|loyalty|booking|workflow" -- helpdesk` finds nothing outside comments
  that cite a copied pattern, and those are removed.

## Risks

- Copying leaves another app's names behind: the grep above is part of the gate.
- Generated jOOQ code drifts: regenerate and commit with each migration, as every app does.
