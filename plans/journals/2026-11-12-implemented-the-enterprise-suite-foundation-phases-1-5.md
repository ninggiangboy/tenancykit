---
title: Implemented the enterprise suite foundation, phases 1-5
date: 2026-11-12
summary: "Cross-cutting core, suite contracts, RabbitMQ messaging, the Platform service with tenancy/entitlement/suite tokens, and centralized users and RBAC. Five commits on dev, every build green."
---

# Implemented the enterprise suite foundation, phases 1-5

## What happened

- **Phase 1** — errors, audit, idempotency, `RequestScope`, `TenantContext` and `TenantSystemId` moved from `billing.platform.api` to `core-kernel`; `ScopedTenantContext` to `core-spring`; the outbox appender, event inbox, relay and audit log to a new `core-messaging` module that addresses the tables with plain SQL (`MessagingTables`) because core cannot see a service's generated schema. `Audit.of` now takes the actor's audit name string, since Billing's `Actor` did not move. ArchUnit gained a `core-messaging-is-self-contained` rule and fixtures.
- **Phase 2** — Billing's 19 event schemas moved to `contracts/events/billing/`; five Platform state-transfer schemas, the `notification.requested` command, three permission catalogs, the AMQP topology/token/runbook READMEs and a Billing test that every `Permission` enum code has its namespaced catalog entry.
- **Phase 3** — `core-messaging` broker path: `EnvelopeCodec`, `SuiteTopology` (with the dead-letter routing key that makes the `<queue>.dlq` binding actually receive), `BrokerRelay` (confirm-then-mark on its own cursor columns) and `AmqpInboundDispatcher` (scope, inbox dedupe, three retries, reject to DLQ). Billing runs the relay in the worker profile, exports every schema-backed type, adds `V22`, regenerates jOOQ, and compose runs RabbitMQ.
- **Phase 4** — a deployable `platform/` build with its own Gradle conventions, tenancy context (tenants, domains, entitlements, versioned `*.changed` events), snapshot API, ES256 token issuer with `/oauth/token` and JWKS, service-client registry, core string-based security types and the generic `PermissionInterceptor`. `PlatformTransactionScopeHook` reuses the RLS template; a `platform_writer` outbox policy lets Platform write events for any tenant.
- **Phase 5** — an `access` context: users, roles, assignments, direct grants, effective-access joins, `user-access.changed` per affected user with a bumped version, tenant provisioning through a tenancy api, the platform-admin bootstrap, Keycloak identity adapter, `/api/v1/me` and the `user-access` snapshot stream. The realm gains `suite-console` and `platform-admin`.

## Verification

- `core`, `billing`, `tenant`, `platform`: `./gradlew build` green (platform after `clean`).
- New tests: envelope/relay/outbox against PostgreSQL + RabbitMQ (publish, outage, dead-letter, inbox dedupe, DLQ), `SuiteTokenIssuer`/token endpoint, tenancy event versions, 1,200-tenant snapshot paging, permission catalog scope, access fan-out, user-access snapshots, platform jOOQ drift and the architecture/meta suites.
- Existing Billing suites still pass, including `OutboxRelayIntegrationTest`, `EventContractIntegrationTest` and the architecture tests.

## Deviations

- `Audit.of(Actor, String)` became `Audit.of(String actorName, String requestId)`; core cannot depend on Billing's `Actor`.
- `spring.rabbitmq.publisher-confirm-type: simple`, not `correlated`: the relay marks rows only after `waitForConfirmsOrDie`, which requires simple confirms.
- Tenant creation moved from the tenancy controller to Platform's provisioning endpoint so the first administrator and role are seeded in the same call; tenancy still owns rename, deactivate and domains.
- `permissions` exists in the schema but is not seeded from YAML yet; the catalog is read from the packaged resources. Seeding belongs with the tenant-system work.
- Billing's `admin-client-id` still says `billing-admin` while the realm now ships `platform-admin`; the cutover in phase 8 must change it together with the bootstrap env var.
- The manual Keycloak smoke test (bootstrap admin logs in through `suite-console`, `/api/v1/me` lists `platform:*`) was not run; the adapter and the path are covered by the fake identity port in tests.

## Next steps

- Phase 6: tenant systems and M2M tokens, then the shared replica and Billing's cutover.
- Add permission-catalog seeding and a suite-level smoke script before the tenant-system migration.
