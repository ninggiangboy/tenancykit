---
title: "Phase 3: Platform Replica, Inbox and Tenant Sweep"
status: todo
---

# Phase 3: Platform Replica, Inbox and Tenant Sweep

## Context Links

- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/config/ReplicaConfiguration.java` — the wiring to copy
- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/replica/feign/{FeignSnapshotTransport,SnapshotFeignApi,PlatformSnapshotErrorMapper}.java`
- `core/core-replica` (`ReplicaWriter`, `ReplicaStream`, `ReplicaProperties`), `core/core-platform-sync` (`SnapshotClient`, `ReplicaBootstrap`)
- `core/core-replica/src/main/resources/dev/ngb/core/replica/replica-tables.sql`
- `platform/apps/app-boot/src/main/java/dev/ngb/platform/boot/replica/SnapshotController.java`
- `platform/apps/app-boot/src/main/resources/application.yml:66-70` — `platform.service-clients` is a **list**
- `platform/apps/app-boot/src/main/java/dev/ngb/platform/boot/config/IntegrationConfiguration.java:75-78` — `ConfiguredServiceClients` maps every entry
- `mailer/apps/app-boot/src/main/java/dev/ngb/mailer/boot/jobs/TenantSweep.java` — the sweep pattern
- `core/core-messaging` (`SuiteTopology`, `EnvelopeCodec`, `AmqpInboundDispatcher`, `AmqpInboundDispatcher.defaultRetry()`)

## Overview

Priority P1, size L. Makes the replica real: the service consumes `platform.events` on its own queue, applies each
state-transfer event with a version guard, and rebuilds from Platform's snapshot API on a fresh database. Until the
rebuild finishes, the worker's listener does not start and readiness stays down, so no notification can be produced
from a half-built view.

This phase also adds the tenant sweep every later job needs: scheduled work has no HTTP request and therefore no tenant
context, so it iterates active tenants explicitly.

## Key Insights

- **Platform support needs no Java change but does need one configuration entry.** `platform.service-clients` is a
  `List<ServiceClient>` (`application.yml:67-70`) and `ConfiguredServiceClients` maps every element
  (`IntegrationConfiguration.java:75-78`), so a second replica consumer is a second list entry with its own id,
  SHA-256 secret hash and `scopes: [platform:replica:read]`. The secret is stored hashed, so ops must provision
  `sha256(secret)` rather than the secret.
- The replica is authoritative because it is **version-guarded**, not because it is fresh. A consumer upserts only when
  the event's `version` exceeds the stored one, so out-of-order arrival and redelivery are both no-ops. This is why no
  synchronous call back to Platform belongs on the event path — there is nothing to repair by asking again.
- The listener must start **after** the snapshot load (`autoStartup=false`) so messages that arrive during the rebuild
  wait in the durable queue instead of racing it; the version guard then drops whatever the snapshot already covered.
- `replica_state` exists precisely so "no rows" and "rebuild in progress" are distinguishable. Readiness is therefore
  a database question, not a row count.
- Notification has no `mailer_tenants`-style projection: the replica already carries `tenants`, so the sweep reads
  `status = 'ACTIVE'` from it. Copying mailer's pattern verbatim would add a table that duplicates the replica.
- Replica tables are Platform-owned data, not tenant-owned rows this service writes; RLS applies to the `dispatch`
  tables created in Phases 5 and 6. The read ports filter by tenant explicitly and that filter is what the tests
  assert.

## Requirements

Functional

- A message on `notification.platform-events` upserts the matching replica row and records the envelope id in
  `event_inbox` inside the same transaction.
- An older or repeated event changes nothing.
- On an empty database the service pages Platform's snapshot for all five streams, writes `replica_state`, and only
  then starts the listener.
- `TenantSweep.forEachActiveTenant(...)` runs work bound to each active tenant, at most N at a time, logging and
  continuing on a per-tenant failure.
- Readiness is `DOWN` until `replica_state` has a row.

Non-functional

- No `@Scheduled` method reads tenant data without the sweep.
- The listener container is named `notification-platform-events` so logs identify it.
- A failed snapshot page surfaces as an unready service, never as an empty replica that looks complete.

## Architecture

```
platform.events ──▶ queue notification.platform-events  (durable, x-dead-letter-exchange: suite.dlx)
                        │
                        ▼
                 AmqpInboundDispatcher  (unit of work + event_inbox dedupe)
                        │
                        ▼
                 ReplicaEventHandler ──▶ ReplicaWriter ──▶ replica tables (version guard)
                        │
                        └──▶ (Phase 5) dispatch acceptance, in the same transaction
```

Boot wiring mirrors `ReplicaConfiguration`: `ReplicaProperties` from `SuiteProperties.replica()` (`platformBaseUrl`,
`clientId`, `clientSecret`, `rebuildOnStart`, `pageLimit`, `requestTimeout`), `SuiteJwtDecoders` with the Platform issuer
plus the Keycloak user issuer, `SnapshotClient` over a Feign transport, `ReplicaBootstrap`, and a
`SimpleMessageListenerContainer` whose `autoStartup` is false until the bootstrap completes.

The `platform` context exposes the read seam through its `api` module:

| Port | Methods |
|---|---|
| `TenantDirectory` | `List<TenantId> activeTenantIds()`, `Optional<TenantView> tenant(TenantId)` |
| `UserDirectory` | `List<UserView> activeUsersOf(TenantId)`, `List<UserView> usersWithPermission(TenantId, String code)`, `Optional<UserView> user(UUID userId)` |

`UserView` carries `userId`, `tenantId`, `email`, `displayName`, `status`, `permissions`, `version`. Phase 5 reads the
previous state through `user(UUID)` before the upsert and compares it after, so transition detection never needs a
second query shape.

## Related Code Files

Create

- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/config/ReplicaConfiguration.java`
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/config/MessagingConfiguration.java`
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/config/SuiteProperties.java`
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/infrastructure/InfrastructureConfiguration.java`
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/infrastructure/tenancy/NotificationTransactionScopeHook.java`
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/replica/PlatformEventsHandler.java` — the
  `InboundEventHandler` that reads the previous row, upserts, then calls the dispatch use case. `core-replica`'s own
  `ReplicaEventHandler.handle` is a one-line `writer.apply(...)` with no hook, so copying it would leave nowhere for the
  previous read and the transition to live
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/replica/feign/{SnapshotFeignApi,FeignSnapshotTransport,PlatformSnapshotErrorMapper}.java`
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/health/ReplicaStateHealthContributor.java` — bean
  name `replicaState`, so the actuator health key is `replicaState`
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/jobs/TenantSweep.java`
- `notification/contexts/platform/src/api/java/dev/ngb/notification/platform/api/{TenantDirectory,UserDirectory,TenantView,UserView}.java`
- `notification/contexts/platform/src/application/java/dev/ngb/notification/platform/application/platform/service/{TenantDirectoryService,UserDirectoryService}.java` — the api ports implemented as `<Interface>Service` in a `service` package, which is what `api-implementations-are-services` requires
- `notification/contexts/platform/src/adapter/java/dev/ngb/notification/platform/adapter/platform/persistence/{JooqTenantDirectory,JooqUserDirectory}.java` — jOOQ stays in the adapter; `application` has no jOOQ on its classpath at all
- `notification/apps/app-boot/src/test/java/dev/ngb/notification/boot/replica/ReplicaBootstrapTest.java`,
  `ReplicaApplyTest.java`, `ReplicaReadinessTest.java`
- `notification/apps/app-boot/src/test/java/dev/ngb/notification/support/FakePlatformSnapshotServer.java`

Modify

- `platform/apps/app-boot/src/main/resources/application.yml` — a second `service-clients` entry reading
  `PLATFORM_NOTIFICATION_SERVICE_CLIENT_ID` and `PLATFORM_NOTIFICATION_SERVICE_CLIENT_SECRET_HASH`
- `notification/apps/app-boot/src/main/resources/application.yml` — `suite.replica.*`, `notification.messaging.*`
- `notification/apps/app-boot/src/main/resources/application-api.yml` — readiness group
  `readinessState, db, replicaState`
- `notification/apps/app-boot/src/main/resources/application-worker.yml` — the same readiness group

## Implementation Steps

1. Add the second service-client entry in Platform's `application.yml` list, with `scopes: [platform:replica:read]` and
   the hash read from a new environment variable. No Platform Java changes.
2. Write `SuiteProperties` (app code, replica, messaging) and `ReplicaProperties`, copying Billing's field names and
   defaults. The replica block is wider than the first four fields: Billing's wiring also reads `platformIssuer`,
   `platformJwkSetUri`, `maxAttempts`, `retryBackoff` and `refreshSkew`, and a short record fails at startup binding.
3. Write `InfrastructureConfiguration`: `TenantContext` (`ScopedTenantContext`), `UnitOfWork` (`SpringUnitOfWork` with
   `NotificationTransactionScopeHook` setting `app.tenant_id`), `Clock`, `IdGenerator` (`UuidV7IdGenerator`),
   `EventInbox` (`JooqEventInbox`). One class owns `clock` and `idGenerator`, as in mailer.
4. Write `MessagingConfiguration`: `MessagingProperties` with `exchange: platform.events`, `exports: Map.of()` (this
   service declares no exchange of its own; Phase 6 fills the producer route), one
   `QueueBinding(queue, platform.events, List.of("tenant.changed", "tenant-domain.changed",
   "tenant-system.changed", "user-access.changed", "entitlement.changed"))`, the relay tuning from the delivery
   settings and `new Document(2)`. Then `Declarables` from `SuiteTopology.declarables(properties)`, the replica handler
   `PlatformEventsHandler`, and the `@Profile({"api","worker"})` dispatcher with `EnvelopeCodec` built inline and
   `AmqpInboundDispatcher.defaultRetry()`.
5. Write the listener container bean **and** `ReplicaBootstrap` for the `worker` profile only. Billing profiles both
   (`ReplicaConfiguration.java:142-150`): an unprofiled `ReplicaBootstrap` needs a container bean that the `api` profile
   would otherwise have to provide, and an unprofiled container makes `api` consume the replica queue as a second
   consumer. `setBeanName("notification-platform-events")`, `setAutoStartup(false)`, started from the bootstrap
   completion callback.
6. Write `ReplicaConfiguration`: `ReplicaWriter`, `SnapshotClient`, `ReplicaBootstrap`, and a health contributor bean
   **named `replicaState`** so its health key is `replicaState`; add that key to the readiness group of **both**
   `application-api.yml` and `application-worker.yml` (`readinessState, db, replicaState`). A bean named
   `…ReadinessIndicator` registers under that name and an `include: replica` entry would match nothing and fail open.
7. Implement the `platform` context read ports. The api interface lives in `platform/api`, its implementation is
   `TenantDirectoryService`/`UserDirectoryService` in `platform/application/.../service`, and the jOOQ queries are
   `Jooq*Directory` classes in `platform/adapter/.../persistence`. Each query filters by tenant explicitly and maps to
   the value views; the application layer never sees a jOOQ type.
8. Write `TenantSweep` from mailer's, reading `activeTenantIds()` through `TenantDirectory`, with
   `notification.jobs.tenant-parallelism` and virtual threads, and per-tenant exception logging. `activeTenantIds()` must
   exclude the platform pseudo-tenant: the replica contains it, and iterating it makes `BrokerRelay` publish with the
   platform tenant id, which mailer refuses by design — every such attempt would become a dead outbox row.
9. Document the rebuild procedure in `docs/notification.md` (created in Phase 8): delete the `replica_state` row and
   restart the worker; `ReplicaBootstrap` sees an incomplete rebuild and repages. No code is added for this.
10. Tests. `FakePlatformSnapshotServer` serves five paged streams from an in-memory list. `ReplicaBootstrapTest` starts
    on an empty database, asserts every table is populated, `replica_state` set, and the listener container not started
    before completion. `ReplicaApplyTest` applies versions 5 then 3 for one user and asserts the row stays at 5, then
    redelivers the same envelope id and asserts `event_inbox` dedupe (no second write). `ReplicaReadinessTest` asserts
    the `replicaState` health contributor is `DOWN` before the bootstrap and `UP` after, and that
    `activeTenantIds()` excludes the platform tenant.

## Todo

- [ ] Platform second service-client config entry
- [ ] `SuiteProperties`, `ReplicaConfiguration`, Feign snapshot transport
- [ ] Listener with `autoStartup=false` and bootstrap completion callback
- [ ] `platform` context read ports over the replica
- [ ] Readiness contributor named `replicaState` included in both profiles
- [ ] `TenantSweep` over active replica tenants
- [ ] Rebuild procedure documented
- [ ] Bootstrap, apply-order, redelivery and readiness tests

## Success Criteria

- `cd notification && ./gradlew :apps:app-boot:test --tests '*Replica*'` passes.
- `ReplicaApplyTest` proves version 3 after version 5 leaves the stored row at version 5, and that a repeated envelope
  id writes nothing.
- `ReplicaBootstrapTest` proves the listener container is not running until `replica_state` has a row.
- `ReplicaReadinessTest` proves `/actuator/health/readiness` is `DOWN` before the bootstrap and `UP` after, in **both**
  the `api` and `worker` profiles.
- `cd platform && ./gradlew build` stays green with the second list entry present.
- With compose, `select count(*) from user_grants` in the notification database equals Platform's user count after a
  fresh start.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| The snapshot client is rejected because the second service client is missing or its hash is wrong | Readiness stays down and the log names the token request; the platform test fixture adds both clients |
| `ReplicaEventHandler` exists and might already do what `PlatformEventsHandler` is for | It does not: `handle` is a one-line `writer.apply(event.type(), payload)` (`core-replica`), so the previous read and the dispatch call must be a handler of our own. Verified before planning |
| The sweep iterates the platform pseudo-tenant and the relay publishes for it | `activeTenantIds()` filters the platform tenant code out; the readiness test asserts it |
| A health contributor named after the class yields a key nobody includes | The bean is named `replicaState` and both profiles include that exact key |
| The bootstrap and listener race | `autoStartup=false` plus the version guard; the test asserts ordering, not just the end state |
| A failed page leaves a partial replica that looks complete | `replica_state` is written only on success; readiness covers it, and the rebuild is idempotent |
| Replica DDL drift (from Phase 2) | The bootstrap test fails if the writer upserts a column the schema lacks |

## Security Considerations

- The replica client credential is its own service client with the narrow `platform:replica:read` scope; it is never
  shared with Billing, and only the SHA-256 hash is stored in Platform's configuration.
- The listener binds tenant scope from the envelope, and broker users are per service — the trust boundary documented
  in `contracts/commands/README.md`. Anything with write on `platform.events` can assert any tenant, which is why the
  notification broker user can only read that exchange.
- The sweep binds a tenant explicitly for every unit of work, so a job cannot read across tenants.
- No tenant data crosses a tenant boundary through the ports: each query takes a tenant id and filters on it.

## Next Steps

Phase 4 adds templates and rendering, which need nothing from this phase. Phase 5 consumes the replica and the
sweep — it is the first phase that turns events into notifications.
