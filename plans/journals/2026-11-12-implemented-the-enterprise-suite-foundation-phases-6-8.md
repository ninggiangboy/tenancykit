---
title: Implemented the enterprise suite foundation, phases 6-8
date: 2026-11-12
summary: "Tenant systems and M2M tokens in Platform, the shared replica library in core, and Billing's cutover to the Platform replica. Three commits on dev, every build green."
---

# Implemented the enterprise suite foundation, phases 6-8

## What happened

- **Phase 6** — a new Platform `integration` context. `tenant_systems` and `api_credentials` moved from Billing with a
  `version` column and a `tenant-system.changed` state-transfer event; credentials gained a `scopes text[]` list.
  `IssueTokenUseCase` keeps the configured service-client path and adds a Tenant System grant: the credential's key id
  is the `client_id`, the secret is the password, and the audience filters the scopes. Scope grantability is checked
  against the permission catalogs, so only `grantable_to_tenant_system: true` codes reach a credential. The token
  endpoint is rate-limited per credential with shared anonymous buckets; create, rotate, revoke and denied requests
  are audited. `SuiteTokenIssuer` now carries `tenant_id`, `tenant_code` and `tenant_system_id` for a system token,
  and the snapshot API grew a `tenant-systems` stream. `V3__integration.sql` adds the three tables.
- **Phase 7** — `core/core-replica`: `ReplicaWriter` (one guarded upsert per stream,
  `insert ... on conflict do update ... where stored.version < excluded.version`), `ReplicaEventHandler`,
  `SnapshotClient` + `ReplicaBootstrap` (snapshot first, then the listener with `autoStartup=false`),
  `ReplicaHostResolver`, `ReplicaActorResolver`, `SuiteJwtDecoders` (two issuers, `aud = suite.app-code`),
  `EntitlementFilter`, the reference DDL and the `ReplicaSchemaAssertions` test fixture. The rebuild-from-zero test
  pages five streams then applies a stale and a fresh event.
- **Phase 8** — Billing cut over. `billing/contexts/platform` is deleted; the codes became
  `BillingPermissions` constants in `shared-kernel`; security uses the replica decoders and actor resolver;
  `TenantContextFilter` reads the replica and `AppEntitlementFilter` answers `app-not-enabled`. `V23` drops the
  identity tables and the rate limiter, adds `version` to the replica tables, and creates `user_grants` and
  `app_entitlements`. OpenAPI was regenerated (identity operations removed, codes renamed). Tests seed the replica
  (`ReplicaFixtures`) and mint tokens with a local key pair (`TestTokenFactory`, `TestJwtDecoders`); the
  Keycloak-backed suites were deleted because those features now live in Platform.

## Verification

- `core`, `platform`, `billing`, `tenant`: `./gradlew build` green. Billing runs 410 tests; the platform
  integration tests cover token audience filtering, disabled/revoked 401s, rotation overlap and the 429 burst;
  core-replica covers the version guard, rebuild-from-zero, host/actor resolution and the schema contract.
- `git grep -nE "billing_users|api_credentials|role_assignments|ApiKeyAuthenticationFilter|contexts:platform"`
  over Billing's code and config returns nothing (historic migrations keep their `create table`, `V23` drops them).

## Deviations

- Billing's webhook codes are `billing:webhook:read|manage` for now; the catalog had them only as `notification:*`
  because the Hub takes webhooks over in phase 9. The two `billing:*` entries are added and noted as moving.
- The two "a machine cannot be granted an administrative code" tests became "a machine without the code is refused
  with 403", since Billing no longer issues credentials — Platform's scope validation is the invariant now.
- `core-messaging` exposes `spring-retry` as `api` because `AmqpInboundDispatcher`'s constructor takes a
  `RetryTemplate`.
- Billing's Stripe webhook tests post to a tenant hostname rather than the platform host, which Billing no longer
  serves.

## Next steps

- Phase 9: the Notification Hub and the webhook move; the two `billing:webhook:*` codes migrate to `notification:*`.
