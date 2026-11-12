---
phase: 4
title: "Platform: tenancy, entitlement, suite tokens"
status: pending
priority: P1
effort: "5d"
dependencies: [3]
---

# Phase 4: Platform service skeleton, tenancy, entitlement and suite tokens

## Goal

Create the deployable `platform/` build. It owns tenants, tenant domains and app entitlements, publishes their `*.changed` events to `platform.events`, serves the snapshot API, and signs suite tokens for service clients (so the snapshot API is protected from day one).

## Key insights

- Billing keeps running unchanged in this phase: its own `tenants` tables are still written by its own platform context. The cutover is phase 8. This keeps every merge green.
- The string-based security types Platform needs (`Actor` with `Set<String>` permissions, `@RequiresPermission(String)`, the permission interceptor) are created in `core/` here. Billing adopts them in phase 8.
- The snapshot API needs authenticated callers before tenant-system credentials exist (phase 6). Service clients are therefore static configuration in Platform (`platform.service-clients[]`: id, SHA-256 of the secret, allowed scopes) and use the same token endpoint.
- `version` is the aggregate's optimistic-lock column, incremented on every change, and copied into the event. The snapshot carries it too, so a replica can merge snapshot rows and live events in any order.
- Platform is multi-tenant itself (tenant admins manage their own users in phase 5), so it reuses Billing's RLS approach: `BillingTransactionScopeHook` is copied as `PlatformTransactionScopeHook` with the same `set_config('app.tenant_id', ?, true)`.

## Files

Create the build (copy from `billing/`, then trim):
- `platform/settings.gradle.kts` (`includeBuild("../core")`; projects `libs:jooq-schema`, `contexts:tenancy`, `apps:app-boot`, `testing:arch-fixtures`), `platform/build.gradle.kts`, `platform/gradle/`, `platform/gradlew*`.
- `platform/build-logic/` copied from `billing/build-logic/` (`java-conventions`, `layered-module-conventions`, `boot-conventions`).
- `platform/contexts/tenancy/` with `domain/tenant` (`Tenant`, `TenantCode`, `TenantDomain`, `Hostname`, `DomainKind`, `TenantStatus`, moved by copy from `billing/contexts/platform/src/domain/java/dev/ngb/billing/platform/domain/tenant/`), `domain/entitlement` (`AppEntitlement`, `AppCode`), and application/adapter packages for `CreateTenantUseCase`, `DeactivateTenantUseCase`, `AddTenantDomainUseCase`, `EnsurePlatformDomainUseCase`, `ResolveHostUseCase`, `EnableAppUseCase`, `DisableAppUseCase`, their queries, `Jooq*Repository` adapters and `TenantController`, `EntitlementController`. Package root `dev.ngb.platform.tenancy`.
- `platform/apps/app-boot/` with `Application`, security, web (`TenantContextFilter`, `ApiExceptionHandler`), jobs (outbox relay and broker relay per tenant), and:
  - `token/SuiteTokenIssuer.java`: signs ES256 JWTs with Nimbus JOSE (`spring-security-oauth2-jose`); key from `PLATFORM_TOKEN_SIGNING_KEY` (PEM) with an optional previous key for rotation; `kid` is the key's thumbprint.
  - `token/TokenController.java`: `POST /oauth/token` (`grant_type=client_credentials`, HTTP Basic client authentication, `audience` form field), answers `{access_token, token_type, expires_in}`.
  - `token/JwksController.java`: `GET /.well-known/jwks.json`.
  - `replica/SnapshotController.java`: `GET /internal/replica/{stream}?after=<cursor>&limit=<1..500>` for `tenants`, `tenant-domains`, `entitlements`; requires scope `platform:replica:read`; cursor is the last `id` (UUID v7 is time-ordered), answers `{items: [...], next: <cursor|null>}` with the same JSON as the event `data` plus `version`.
- `platform/apps/app-boot/src/main/resources/db/migration/V1__baseline.sql`: `platform_app` role, `app_tenant_id()`, `enable_tenant_rls`, `tenants` and `tenant_domains` (as in Billing's `V1__tenancy.sql`, plus `version bigint not null default 0`), `app_entitlements (tenant_id, app_code, enabled, version, updated_at, primary key (tenant_id, app_code))`, `outbox` (with the broker cursor columns from phase 3), `event_inbox`, `audit_log`, `job_leases`, `job_runs`. Seed the platform pseudo-tenant row `00000000-0000-7000-8000-000000000001`.
- `platform/libs/jooq-schema/` with `JooqSchemaTest` copied from Billing.
- `platform/apps/app-boot/src/test/java/dev/ngb/platform/architecture/{ArchitectureRules,ArchitectureTest,ArchitectureMetaTest}.java` adapted from Billing, plus `platform/testing/arch-fixtures/`.
- `core/core-kernel/src/main/java/dev/ngb/core/security/{Actor,ActorType,CurrentActor,PermissionScope,RequiresPermission,PublicEndpoint,Authenticated}.java`: string permission codes; `Actor.has(String code)`.
- `core/core-spring/src/main/java/dev/ngb/core/spring/security/PermissionInterceptor.java`: generic version of `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/web/PermissionInterceptor.java` that reads the core annotation.
- `.github/workflows/platform.yml`: copy of `billing.yml` with `platform/**` and `core/**` paths.

Modify:
- `core/settings.gradle.kts` and the core ArchUnit boundary test: new packages.
- `docker-compose.yml`: a `platform` database (the full per-service layout lands in phase 13; for now add the database and role to the existing Postgres through an init script `ops/postgres/init/10-platform.sql`).

## Events

| Change | Event | Exchange |
|---|---|---|
| Tenant created, renamed, deactivated | `tenant.changed` | `platform.events` |
| Domain added | `tenant-domain.changed` | `platform.events` |
| App enabled or disabled | `entitlement.changed` | `platform.events` |

Every use case writes the aggregate (with its incremented `version`) and the event through `EventPublisher` in the same `UnitOfWork`.

## Implementation steps

1. Copy the build skeleton and conventions; `./gradlew build` green with an empty app.
2. Add the core security types and the generic interceptor, with unit tests.
3. Copy the tenancy domain and use cases from Billing, renaming packages; add `version` handling and event publishing; port their tests from `billing/contexts/platform/src/test/`.
4. Add entitlement domain, use cases and API (`PUT /api/v1/tenants/{tenantId}/apps/{appCode}` with `{enabled}`; permission `platform:entitlement:manage`; `AppCode` is `billing` or `notification`, loaded from `contracts/permissions/*.yaml` file names copied into resources at build time).
5. Add the token issuer, token endpoint, JWKS and service-client configuration; security config accepts Keycloak JWTs (users, phase 5) and its own tokens (service clients).
6. Add the snapshot endpoint and its paging test (1,200 tenants page correctly; `after` past the end returns `next: null`).
7. Integration test with Testcontainers Postgres and RabbitMQ: creating a tenant publishes `tenant.changed` with `version` 1; renaming publishes `version` 2.
8. ArchUnit rules and arch-fixtures; the workflow file.

## Todo

- [x] `platform/` build, conventions, CI workflow
- [x] Core string-based security types and interceptor
- [x] Tenancy and domain use cases with versions and events
- [x] Entitlement use cases and API
- [x] Token issuer, `/oauth/token`, JWKS, service clients
- [x] Snapshot API with cursor paging
- [x] Migrations, jOOQ schema, `JooqSchemaTest`
- [x] ArchUnit rules and fixtures

## Verification

- `cd platform && ./gradlew build` green.
- `cd billing && ./gradlew build` and `cd tenant && ./gradlew build` still green (core changed).
- Token test: a service-client token verifies against the JWKS, carries only scopes of its `audience`, and expires in 600 seconds; a wrong secret answers 401 without saying why.

## Risks

- Two copies of tenant logic (Platform and Billing) exist until phase 8. Accepted; Billing's copy is deleted there.
- Losing the signing key invalidates all tokens, which is harmless because they live 10 minutes. Rotation keeps the previous key in the JWKS for one token lifetime.

## Security

- Service-client secrets are stored as SHA-256 hashes in configuration and compared in constant time (reuse `ApiKey` hashing).
- `/internal/replica/*` requires `platform:replica:read`; no user role can hold it (catalog `scopes: []`, service clients only).
- The private signing key comes from env or a mounted file, never from the repo.
