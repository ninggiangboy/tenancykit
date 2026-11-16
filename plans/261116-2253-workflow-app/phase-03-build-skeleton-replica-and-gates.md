---
phase: 3
title: "Build skeleton, replica and gates"
status: pending
priority: P1
effort: "L"
dependencies: [2]
---

# Phase 3: Build Skeleton, Replica and Gates

## Goal

A `workflow/` build that starts in api, worker and migrate runtimes, authenticates suite tokens, enforces the `workflow`
entitlement, replicates Platform data (tenants, domains, tenant systems, users and roles as needed), delivers its outbox to
signed webhooks, and fails the build on any layering or isolation violation, with no business feature yet.

## Context

Copy the shape of `mailroom/` (committed and final) and crm's committed `webhook` context (`crm/contexts/webhook`, copied from
Survey): `settings.gradle.kts`, `build.gradle.kts`, `build-logic/`, `apps/app-boot`, `libs/{shared-core,jooq-schema}`,
`testing/arch-fixtures`. Do not copy from in-flight `attribution/`, `survey/` or `booking/`. The replica must also keep
`tenant_domains` (the engine needs a Tenant's hostname to call apps) and, from phase 2, the Tenant System `purpose`.
Contexts: `platform` (replica), `definitions`, `runtime`, `triggers`, `connections`, `tasks`, `webhook`.

## Files to create

- `workflow/settings.gradle.kts`, `build.gradle.kts`, `build-logic/`, wrapper, `libs/shared-core` (ids, `WorkflowPermissions`),
  `libs/jooq-schema` (generated, committed), `contexts/{platform,definitions,runtime,triggers,connections,tasks,webhook}`
- `apps/app-boot`: `Application`, `config/*`, `replica/*`, `health/*`, `web/*` (tenant context, entitlement filter),
  `jobs/{TenantSweep,OutboxRelayJob,WebhookDeliveryJob,HousekeepingJob}`, `Dockerfile`, `application{,-api,-worker,-migrate}.yml`
- `V1__baseline.sql`: roles, the RLS template, replica tables, `replica_state`, `event_inbox`, `outbox`, webhook endpoint and
  delivery tables, job lease and run tables, the reserved Platform tenant
- Tests: `RowLevelSecurityTest`, `JooqSchemaTest`, `ReplicaApplyTest`, `PermissionCatalogTest`, `OpenApiContractTest`,
  `ArchitectureTest`, `ArchitectureMetaTest`, `ModulithVerificationTest`, and the copied webhook tests

## Tasks

1. Copy the skeleton, rename to `dev.ngb.workflow`, delete every mailroom-specific class and table. No file may mention
   mailroom except a comment citing the precedent.
2. Messaging: queue `workflow.platform-events` bound to Platform's state-transfer keys with the suite DLX pair; declare no
   exchange. The outbox relays in process to webhooks only, like crm; the startup assertion is that the webhook listener exists.
   The `workflow.runnable` wake-up queue is declared later (phase 6) as the app's own internal queue.
3. Package `contracts/events/workflow` and `contracts/permissions/workflow.yaml` into the jar like crm does.
4. Architecture rules: the layered package rule, no context depends on another's adapter, `runtime` is the only context that
   executes nodes, and no class outside `connections` opens an outbound HTTP connection (phase 9 enforces it).
5. RLS template on every tenant table from day one; the `workflow_app` role cannot bypass it.

## Validation

- `./gradlew build` green in `workflow/`; `docker compose up` starts the app in the three runtimes; replica catches up.
- `RowLevelSecurityTest` proves a tenant sees only its rows on every table created so far.
- ArchUnit and Modulith gates green; deliberately breaking a layer in a scratch edit fails the build.

## Risks

- Copying a skeleton drags hidden coupling; the arch tests are the guard. Replica state needed later (roles) is added in
  phase 11, not guessed here.

## Notes — what the skeleton contains, and what is deferred

Built from `mailroom/` (committed and final) and trimmed to the workflow's contexts:

- `workflow/` builds green (`./gradlew build`): the suite isolation gate, ArchUnit, Modulith, the jOOQ drift test, the
  replica apply test, the RLS test, the permission catalog test, the OpenAPI contract test and both runtime startup tests.
- Contexts: `platform` (the replica) plus empty `definitions`, `runtime`, `triggers`, `connections` and `tasks` modules,
  each with the layered source sets so the first feature has a home.
- `V1__baseline.sql`: the roles, the RLS template, the five replica tables (with the Tenant System `purpose`), the
  reserved Platform tenant, `event_inbox`, `replica_state`, `outbox`, the three webhook tables and the two job tables.
- `workflow.platform-events` is declared and bound to `platform.events` with the five state-transfer keys and its
  dead-letter pair; the worker consumes it, the api runtime does not. No exchange is declared, so the broker user stays
  a pure consumer.
- The jOOQ schema is regenerated from `V1__baseline.sql` and committed.

Deferred, with the phase that owns it:

- The copied `webhook` context, the in-process outbox→webhook relay, `WebhookDeliveryJob`, the `workflow` event schemas
  and their header set are **phase 13**. The three webhook tables exist now; nothing writes them yet.
- The three compose runtimes, the Caddy route, CI and the deployment entries are **phase 15**. `application-api.yml`,
  `application-worker.yml` and `application-migrate.yml` are in place; the containers are not.
- The OpenAPI document starts empty (`paths: {}`); phase 5 fills the definition endpoints.
- `archunit.properties` sets `archRule.failOnEmptyShould=false` because the skeleton's empty contexts match no class
  yet. It is deleted when every layer a rule names holds a class; the `ArchitectureMetaTest` fixtures still prove each
  rule can fail.
