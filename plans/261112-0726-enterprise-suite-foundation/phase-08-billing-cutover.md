---
phase: 8
title: "Billing cutover"
status: pending
priority: P1
effort: "4d"
dependencies: [7]
---

# Phase 8: Billing cutover to Platform

## Goal

Billing stops owning tenants, users, roles, tenant systems and credentials. It reads them from `core-replica`, accepts Keycloak and Platform tokens, enforces `billing:*` permission codes, and `billing/contexts/platform` is deleted.

## Key insights

- What remains of `billing/contexts/platform` after phase 1 is: `api/actor/*` (replaced by core security types), the domain, application and adapter code for tenant, user, role, credential and tenant system (now in Platform), and `PlatformActors` (one constant, moves to Billing's `app-boot`).
- `RequestScope.platform()` is still used by Billing outside HTTP: `payment/.../ReceiveStripeWebhookUseCase.java`, `boot/jobs/TenantSweep.java`. The platform pseudo-tenant row therefore stays in Billing's `tenants` replica (Platform emits it, see phase 2). Billing's HTTP surface no longer serves the platform host.
- Permission constants: `@RequiresPermission` needs compile-time strings, so the enum becomes `BillingPermissions` with `public static final String INVOICE_READ = "billing:invoice:read";` and so on. The OpenAPI `x-permission` values change; that is an intended contract change.
- Old rows are not migrated (out of scope). A forward migration drops the identity tables and adds the replica columns.

## Files

Create:
- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/security/BillingPermissions.java` (codes from `contracts/permissions/billing.yaml`) and a test that the constants equal the catalog.
- `billing/apps/app-boot/src/main/resources/db/migration/V23__suite_replica.sql`: drop `api_credentials`, `user_permissions`, `role_assignments`, `role_permissions`, `roles`, `permissions`, `billing_users`, `rate_limit_buckets`; `alter table tenants|tenant_domains|tenant_systems add column version bigint not null default 0`; create `user_grants` and `app_entitlements` per `core/core-replica/src/main/resources/dev/ngb/core/replica/replica-tables.sql` with RLS on `user_grants`.

Modify:
- Every `@RequiresPermission(Permission.X)` and `actor.permissions().contains(Permission.X)` in `billing/contexts/*` and `billing/apps/app-boot` (mechanical rewrite to `BillingPermissions.X` and `actor.has(...)`).
- `billing/contexts/*/build.gradle.kts`: drop `project(":contexts:platform")`, depend on `dev.ngb.core:core-kernel` (and `core-replica` only in `app-boot`).
- `billing/settings.gradle.kts`: remove `contexts:platform`.
- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/security/SecurityConfiguration.java`: use `SuiteJwtDecoders` and `ReplicaActorResolver`; remove the API-key bearer resolver branch and `ApiKeyAuthenticationFilter`; keep portal-session authentication and the Stripe webhook and public endpoints unchanged.
- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/web/TenantContextFilter.java`: use `ReplicaHostResolver`; add `EntitlementFilter` for app `billing`.
- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/web/PermissionInterceptor.java`: deleted in favour of the core interceptor.
- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/config/PlatformConfiguration.java`: deleted; replica configuration added to `InfrastructureConfiguration`.
- `billing/apps/app-boot/src/main/resources/application.yml`: remove Keycloak admin client and bootstrap settings; add `suite.app-code: billing`, replica and RabbitMQ queue `billing.platform-events`.
- `billing/apps/app-boot/src/test/java/dev/ngb/billing/architecture/ArchitectureRules.java`: drop platform-module rules; add "no `dev.ngb.billing..` class depends on `dev.ngb.platform..` or `dev.ngb.notification..`".
- `billing/apps/app-boot/src/test/...`: security and controller tests mint tokens with a test key pair (one helper for user tokens and one for tenant-system tokens) and seed replica rows instead of creating users and credentials.
- `contracts/openapi/billing.yaml`, `contracts/openapi/billing-tenant-system.yaml`: regenerate with `./gradlew :apps:app-boot:test --tests '*OpenApiContractTest' -Dopenapi.update=true`; tenant, user, role, tenant-system and credential operations disappear.
- `.env.example`: drop `BILLING_KEYCLOAK_ADMIN_CLIENT_SECRET`, `BILLING_BOOTSTRAP_ADMIN_EMAIL`; add `BILLING_REPLICA_CLIENT_SECRET`.

Delete:
- `billing/contexts/platform/` (whole module, including tests).
- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/security/{ApiKeyAuthenticationFilter,JwtActorConverter}.java`, `boot/startup/PlatformBootstrap.java`, `boot/infrastructure/security/PostgresRateLimiter.java`.

## Implementation steps

1. Add `BillingPermissions` and its catalog test; rewrite annotation and check sites.
2. Swap security: two-issuer decoder, replica actor resolver, host resolver, entitlement filter. Rewrite security tests first, then make them pass.
3. Migration `V23`, jOOQ regeneration, replica configuration, `ReplicaSchemaAssertions` test against Billing's database.
4. Delete the platform module and the dead app-boot classes; fix compile errors.
5. Regenerate OpenAPI contracts; review the diff (only identity operations removed and permission codes renamed).
6. Update ArchUnit rules and fixtures.
7. Full build.

## Todo

- [ ] `BillingPermissions` and rewritten checks
- [ ] Security on replica: two issuers, host resolver, entitlement filter
- [ ] `V23` migration and jOOQ regeneration
- [ ] `contexts:platform` and dead classes deleted
- [ ] OpenAPI contracts regenerated and reviewed
- [ ] ArchUnit rules updated
- [ ] Full build green

## Verification

- `cd billing && ./gradlew build` green (all suites, including `RowLevelSecurityIntegrationTest` and `ArchitectureMetaTest`).
- `git grep -nE "billing_users|api_credentials|role_assignments|ApiKeyAuthenticationFilter|contexts:platform" -- 'billing/*.java' 'billing/*.kts' 'billing/*.yml'` returns nothing. Historic migrations keep their `create table` statements; `V23` drops them.
- A request with an old `bk_...` API key answers 401.

## Risks

- Large mechanical diff across eight contexts. Mitigation: do the permission rewrite as its own commit before the security swap, each commit green.
- Tests relied on creating users through Billing's API. Mitigation: a `ReplicaFixtures` test helper seeds `tenants`, `user_grants`, `tenant_systems` and `app_entitlements` directly.

## Security

- Billing accepts exactly two issuers and checks `aud = billing`; any other issuer or audience is 401.
- The tenant-system actor's tenant must equal the host's tenant; mismatch is 403, as before.
