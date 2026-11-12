---
phase: 7
title: "Shared replica and snapshot rebuild"
status: pending
priority: P1
effort: "3d"
dependencies: [4, 5, 6]
---

# Phase 7: Shared replica and snapshot rebuild

## Goal

Build `core/core-replica`: one library every app uses to keep local replicas of Platform data current from `platform.events`, rebuild them from the snapshot API, resolve the tenant from the hostname, turn a verified token into an `Actor`, and reject tenants that have not enabled the app. App number three then needs configuration and a migration, not code.

## Key insights

- Replica table names match Billing's existing tables (`tenants`, `tenant_domains`, `tenant_systems`), so Billing's composite FKs and RLS stay untouched. Two new tables: `user_grants` and `app_entitlements`.
- Upserts carry a version guard: `insert ... on conflict (id) do update set ... where <table>.version < excluded.version`. An older or duplicate event is a no-op, so the order of snapshot pages and live messages does not matter.
- **Rebuild order:** start the listener container only after the snapshot load finishes. Messages that arrive meanwhile wait in the durable queue and are applied afterwards; the version guard drops any that the snapshot already covered.
- Platform never hard-deletes these rows (tenants and tenant systems are deactivated, users disabled, apps disabled), so a rebuild is "upsert everything", never "truncate then load". This matters because Billing's business tables reference `tenants` and `tenant_systems`.
- Each app stores only its own permission codes in `user_grants.permissions` (prefix `billing:` or `notification:`).

## Files

Create `core/core-replica/` (`api(project(":core-messaging"))`, Spring Security OAuth2 resource server, Spring Web client):
- `src/main/java/dev/ngb/core/replica/ReplicaProperties.java`: `suite.app-code`, `suite.replica.platform-base-url`, `suite.replica.client-id/secret`, `suite.replica.rebuild-on-start` (default: only when `tenants` holds no tenant rows other than the platform pseudo-tenant).
- `ReplicaTables.java`: plain-SQL jOOQ references for the five tables.
- `ReplicaWriter.java`: one guarded upsert per stream.
- `ReplicaEventHandler.java` (`InboundEventHandler` for the five `*.changed` types).
- `SnapshotClient.java`: fetches a service token from `/oauth/token` (audience `platform`), pages each stream until `next` is null.
- `ReplicaBootstrap.java`: `ApplicationRunner` that loads snapshots when needed, then starts the `platform-events` listener container (`autoStartup=false` on that container).
- `web/ReplicaHostResolver.java`: hostname → `RequestScope` from `tenant_domains` (platform host → platform scope; unknown host → 404, as today in `ResolveHostUseCase`).
- `security/ReplicaActorResolver.java`: Keycloak JWT → `Actor` from `user_grants` (subject, tenant match, active); Platform JWT → `Actor` of type `TENANT_SYSTEM` from claims, after checking `tenant_systems.status = 'ACTIVE'` and that `tenant_id` matches the resolved tenant.
- `security/SuiteJwtDecoders.java`: a `JwtIssuerAuthenticationManagerResolver` for exactly two issuers (Keycloak realm and Platform), each with its audience check (`suite.app-code`).
- `web/EntitlementFilter.java`: 403 with problem type `app-not-enabled` when `app_entitlements` has no enabled row for the resolved tenant and app.
- `src/main/resources/dev/ngb/core/replica/replica-tables.sql`: reference DDL for apps that start fresh (the Hub).
- `src/testFixtures/java/dev/ngb/core/replica/ReplicaSchemaAssertions.java`: asserts an app database has the required columns and unique keys.
- Tests: Testcontainers Postgres + RabbitMQ + a WireMock (or `MockRestServiceServer`) snapshot server.

Modify:
- `core/settings.gradle.kts`: include `core-replica`.
- `platform/`: Platform does not consume its own replica; no change.

## Implementation steps

1. Module skeleton, properties, table references, reference DDL.
2. `ReplicaWriter` with guarded upserts; unit-level DB tests per stream (newer version wins, older is ignored, equal is ignored).
3. `ReplicaEventHandler` wired through `AmqpInboundDispatcher`.
4. `SnapshotClient` and `ReplicaBootstrap`; test "rebuild from zero": empty database, snapshot server with 3 tenants, 2 domains, 1 tenant system, 2 users, 2 entitlements → all rows present with versions; then a queued `tenant.changed` with an older version is ignored and a newer one applies.
5. `ReplicaHostResolver`, `ReplicaActorResolver`, `SuiteJwtDecoders`, `EntitlementFilter` with focused tests (tokens minted in tests with a local key pair and a stub JWKS).
6. Test fixture `ReplicaSchemaAssertions`.

## Todo

- [ ] `core-replica` module and CI coverage in `core.yml`
- [ ] Guarded upserts for five streams
- [ ] Live consumption through the inbound dispatcher
- [ ] Snapshot client and ordered bootstrap
- [ ] Host resolver, actor resolver, two-issuer decoders, entitlement filter
- [ ] Rebuild-from-zero integration test

## Verification

- `cd core && ./gradlew :core-replica:test` green, including the rebuild-from-zero test.
- `cd billing && ./gradlew build`, `cd platform && ./gradlew build`, `cd tenant && ./gradlew build` green.

## Risks

- Replica lag lets a removed permission work for a few seconds. Accepted trade-off of the design; measured in phase 13 (target p95 below 5 seconds).
- Snapshot during heavy writes. Safe by construction: rows and events carry versions and the guard keeps the newest.

## Security

- The service-client secret for the snapshot API comes from env per app.
- The actor resolver never trusts a client-supplied tenant: the tenant comes from the hostname for users and must equal the token's `tenant_id` for tenant systems.
