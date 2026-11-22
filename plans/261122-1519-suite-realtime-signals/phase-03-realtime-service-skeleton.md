---
phase: 3
title: "Realtime service skeleton"
status: pending
priority: P1
effort: "2d"
dependencies: [2]
---

# Phase 3: Realtime service skeleton

## Goal

Create `backend/services/infra/realtime/` as a build that starts, migrates, authenticates a user and keeps the Platform
replica, following the pattern of `infra/notification` so the later phases only add behaviour.

## Context

- `backend/services/infra/notification/` is the closest template: `settings.gradle.kts` with `libs:shared-core`,
  `libs:jooq-schema`, `contexts:*`, `apps:app-boot`, `testing:arch-fixtures`; runtimes `migrate`, `api`, `worker`;
  `ReplicaConfiguration`, `SecurityConfiguration`, `TenantContextFilter`, `ReplicaStateHealthContributor`,
  `PlatformEventsHandler`.
- Core modules available: `core-kernel`, `core-spring`, `core-messaging`, `core-replica`, `core-platform-sync`,
  `core-suite`, `core-feign`, `core-arch`, `core-openapi`.
- Notification's authorization table is per endpoint group because `SuiteEntitlementFilter` passes platform scope and
  `@Authenticated` admits a machine. Realtime has one group and must refuse a non-user principal.

## Requirements

- Root package `dev.ngb.realtime`; Gradle name `realtime`; contexts `platform` (replica) and `signal` (added in the next
  phase, declared empty here so the module graph is final).
- Runtimes `migrate`, `api`, `worker`, plus the default profile that runs `api` and `worker` together for dev and tests.
- Own database `realtime`, own roles `realtime_owner` (migrations) and `realtime_app` (runtime, no bypass of RLS).
- Replica tables for the five Platform streams, `replica_state`, `event_inbox`, `outbox` (the contract in
  `core-messaging`'s `schema-contract.md`), RLS on tenant-owned tables.
- JWT resource-server security like notification; a non-user principal (machine) is refused; no permission code and no
  entitlement filter (the design asks for neither).
- Readiness group includes `replicaState`; the worker is the only runtime that rebuilds the replica.
- ArchUnit and Modulith verification tests in the service, as in notification.

## Architecture

```
realtime
  libs/shared-core        shared kernel types (signal ids, audience value objects)
  libs/jooq-schema        generated from the migrations
  contexts/platform       replica reader ports (user grants, tenants), ReplicaWriter use
  contexts/signal         (phase 4) ingest, replay, fan-out, stream
  apps/app-boot           configuration, security, jobs, web
```

Runtime split: `worker` = `platform.events` listener, `realtime.commands` listener, outbox relay, housekeeping;
`api` = HTTP and the stream, plus the per-replica fan-out listener (phase 5).

## Files

Create (mirroring notification's structure):
- `backend/services/infra/realtime/settings.gradle.kts`, `build.gradle.kts`, `build-logic/`, `gradle/`, `gradlew*`
- `libs/shared-core`, `libs/jooq-schema`, `contexts/platform`, `contexts/signal`, `apps/app-boot`, `testing/arch-fixtures`
- `apps/app-boot/src/main/resources/db/migration/V1__baseline.sql` (replica tables, `replica_state`, `event_inbox`,
  `outbox`, RLS policies) and `application*.yml` for the profiles
- `apps/app-boot/Dockerfile`
- `RealtimeSettings`, `SuiteProperties`, `SecurityConfiguration`, `ActorConfiguration`, `TenantContextFilter`,
  `ReplicaConfiguration`, `MessagingConfiguration` (consumes `platform.events` on `realtime.platform-events`),
  `ReplicaStateHealthContributor`, architecture tests

Reuse, do not copy logic: copy only the wiring shape; any logic that would be duplicated in a second service belongs in
core and is raised as a follow-up, not forked silently.

## Steps

1. Read notification's `settings.gradle.kts`, `build-logic`, `app-boot` config classes and the architecture tests; list
   exactly which classes are wiring and which are notification-specific.
2. Scaffold the build from that list; drop the dispatch context; keep `platform` and add the empty `signal` context.
3. Write `V1__baseline.sql` from the replica reference DDL in `core-replica` and the outbox/inbox contracts; add RLS.
4. Configure `MessagingProperties`: consumes `platform.events` on `realtime.platform-events` (queue and DLQ via
   `SuiteTopology`), `exports` empty for now (the relay assertion is added in phase 4), `Document(2)` as notification.
5. Wire security: JWT decoder, tenant context from the host, refusal of a non-user principal, no entitlement filter.
6. Add the Testcontainers support classes (`PostgresContainerConfig`, `RabbitContainerConfig`) and the arch tests; make
   `./gradlew build` pass with an empty `signal` context.
7. Replica test: feed a state-transfer envelope through the handler and assert the version guard and the grants table
   (copy the shape of notification's `ReplicaApplyTest`).

## Todo

- [ ] Gradle build and modules created
- [ ] Baseline migration with RLS
- [ ] Settings, security, tenant context, readiness
- [ ] Platform replica consumer and rebuild
- [ ] Arch and Modulith tests pass
- [ ] Replica apply test passes

## Verification

- `cd backend/services/infra/realtime && ./gradlew build` passes.
- Boot test: with the migrate profile the schema applies; with `api` plus `worker` on a fresh database, the api stays
  not ready until the replica rebuild finished, then becomes ready.
- A machine token calling any `/api/**` route gets 403; no token gets 401.

## Risks

- Copying notification wholesale drags in dispatch concepts. Mitigation: step 1's explicit wiring list.
- The replica rebuild needs a Platform service client; that registration is phase 6, so local runs wait on it.

## Security

- `realtime_app` has no `BYPASSRLS` and no DDL rights; only the migrate step writes schema.
- Replica tables carry Platform-owned data and no RLS (as in notification); every read port filters by tenant.

## Next

Phase 4.
