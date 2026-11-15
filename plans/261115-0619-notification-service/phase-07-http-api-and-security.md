---
title: "Phase 7: HTTP API, Security and Permissions"
status: todo
---

# Phase 7: HTTP API, Security and Permissions

## Context Links

- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/security/SecurityConfiguration.java`
- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/web/{TenantContextFilter,AppEntitlementFilter,ApiExceptionHandler}.java`
- `core/core-kernel/src/main/java/dev/ngb/core/security/{RequiresPermission,CurrentActor}.java`,
  `core/core-spring/src/main/java/dev/ngb/core/spring/security/PermissionInterceptor.java`
- `core/core-suite/src/main/java/dev/ngb/core/suite/web/{SuiteEntitlementFilter,SuiteTenantFilter,SuiteJwtDecoders}.java`
- `billing/apps/app-boot/src/test/java/dev/ngb/billing/contract/{OpenApiContractTest,PermissionCatalogTest}.java`
- `billing/contexts/customer/src/adapter/java/dev/ngb/billing/customer/adapter/customer/web/CustomerController.java:106`
- `contracts/permissions/notification.yaml` (Phase 1)

## Overview

Priority P1, size L. Gives the service its front door: the caller's own notification list and read state, their
preference, the tenant's template overrides and settings, and the dispatch ledger — all behind the app's audience, its
entitlement row and the four permission codes.

This is where the app becomes a real member of the suite rather than a background consumer, and where the two
authorization layers that already exist in the suite get applied: tenant resolution from the Host, and an entitlement
check that refuses a tenant that has not enabled the app.

## Key Insights

- Billing's security is not a filter chain to invent: `SuiteJwtDecoders` verifies exactly two issuers (Platform's suite
  tokens and Keycloak's user tokens) against one audience, and `SuiteActorConverter` produces the `CurrentActor` every
  controller uses. Notification copies the shape with `audiences: notification`.
- `PermissionInterceptor` **fails closed** — an endpoint with no access rule throws
  `IllegalStateException("endpoint declares no access rule")`. The escape hatch is `@Authenticated`, and it admits
  **`platform-user`, `tenant-user` and `tenant-system`** (`WebConfiguration.java:165-166` in Billing is where that list
  is declared). So the marker alone does not mean "a tenant user": the self-service endpoints need an explicit guard
  that refuses a platform scope and a machine principal, and `notification_preferences` must never receive a row keyed
  by a tenant-system id.
- `AppEntitlementFilter` already exists in core and reads `app_entitlements` from the replica. Notification can use it
  unchanged with `app-key: notification`; the only new thing is the entitlement row a Platform admin creates.
- A Tenant System credential carries only codes marked `grantable_to_tenant_system: true`; all four of ours are false,
  so admin endpoints are unreachable for machines. User endpoints need a second guard: a machine principal has no user
  id, so the controller must refuse a non-user actor with 403 rather than return an empty list.
- `SuiteEntitlementFilter` passes **platform scope unconditionally** (`SuiteEntitlements.java:28-30`), so the
  entitlement check alone leaves `platform.localhost/notification/...` open to any platform user. Every endpoint
  therefore also requires a tenant scope, refused with `403` — a policy rule this service owns, because the core filter
  is deliberately permissive for platform operators.
- `before`-cursor paging on the UUID v7 id alone (`where id < :before`, `order by id desc`) gives stable pagination with
  no offset and no tuple comparison: the id is time-ordered, so it doubles as the sort key. `created_at` is not used in
  the comparison, and no second lookup is needed.
- The query side is what lets the architecture's `query-side-uses-no-domain-aggregates` rule finally have subjects;
  Phase 2's escape-hatch entry is removed in this phase.

## Requirements

Functional

| Method | Path | Access |
|---|---|---|
| `GET` | `/api/v1/notifications?unread=&before=&limit=` | the caller's own rows only |
| `POST` | `/api/v1/notifications/{id}/read` | own row; 404 for another user's id |
| `POST` | `/api/v1/notifications/read-all` | own rows |
| `GET` | `/api/v1/notifications/preferences` | own preference |
| `PUT` | `/api/v1/notifications/preferences` | own preference: `locale`, `mutedTypes` |
| `GET` | `/api/v1/templates` | `notification:template:read` |
| `GET` | `/api/v1/templates/{key}?locale=` | `notification:template:read`, resolved variant |
| `PUT` | `/api/v1/templates/{key}/{locale}` | `notification:template:manage` |
| `DELETE` | `/api/v1/templates/{key}/{locale}` | `notification:template:manage` |
| `GET` | `/api/v1/settings` | `notification:config:manage` |
| `PUT` | `/api/v1/settings` | `notification:config:manage`: `defaultLocale`, `enabledTypes` |
| `GET` | `/api/v1/email-dispatches?status=&before=&limit=` | `notification:dispatch:read` |

- Query results are ordered newest first (`id desc`), `limit` defaults to 25 and is capped at 100, and the list response
  carries `unreadCount` so the smoke script and a UI need one call, not two.
- Unknown template key, locale or type is `404`; an invalid body is `400`; both through the core problem body.
- A tenant without the `notification` entitlement gets `403`, and so does any platform-scope caller.

Non-functional

- Controllers hold no SQL; they call a query class or a use case.
- Read models never instantiate a domain aggregate; they map jOOQ rows to response records.
- The OpenAPI document is generated, committed and compared by a test.

## Architecture

```
request ─▶ SuiteTenantFilter (Host → tenant)        [core]
       ─▶ SuiteEntitlementFilter (app_entitlements) [core]
       ─▶ JWT: two issuers, audience "notification" [core JwtDecoders]
       ─▶ SuiteActorConverter → CurrentActor        [core]
       ─▶ PermissionInterceptor + @RequiresPermission [core]
       ─▶ controller (adapter/<feature>/web) → query class or use case
                     │
                     ├─ Jooq*ReadRepository (adapter) → replica + dispatch tables
                     └─ TemplateOverrideUseCase / SettingsUseCase (application)
```

Permission constants live in `dev.ngb.notification.shared.NotificationPermissions` and are asserted equal to
`contracts/permissions/notification.yaml` by a test, exactly as `BillingPermissions` is.

## Related Code Files

Create

- `notification/libs/shared-core/src/main/java/dev/ngb/notification/shared/NotificationPermissions.java`
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/security/SecurityConfiguration.java`
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/web/{TenantContextFilter,AppEntitlementFilter,ApiExceptionHandler}.java`
- `notification/contexts/dispatch/src/application/java/dev/ngb/notification/dispatch/application/notification/query/{ListNotificationsQuery,ListDispatchesQuery,ListTemplatesQuery,NotificationView,DispatchView,TemplateView}.java`
- `notification/contexts/dispatch/src/application/java/dev/ngb/notification/dispatch/application/notification/command/{MarkNotificationReadUseCase,SavePreferenceUseCase,SaveTemplateOverrideUseCase,SaveTenantSettingsUseCase}.java`
- `notification/contexts/dispatch/src/adapter/java/dev/ngb/notification/dispatch/adapter/notification/persistence/{JooqNotificationReadRepository,JooqDispatchReadRepository,JooqTemplateReadRepository}.java`
- `notification/contexts/dispatch/src/adapter/java/dev/ngb/notification/dispatch/adapter/notification/web/{NotificationController,NotificationPreferenceController,TemplateController,TenantSettingsController,DispatchController}.java`
- `notification/apps/app-boot/src/test/java/dev/ngb/notification/security/SuiteTokenSecurityTest.java`
- `notification/apps/app-boot/src/test/java/dev/ngb/notification/contract/{OpenApiContractTest,PermissionCatalogTest}.java`
- `notification/apps/app-boot/src/test/java/dev/ngb/notification/dispatch/NotificationApiTest.java`
- `contracts/openapi/notification.yaml` — generated by the contract test

Modify

- `notification/apps/app-boot/src/main/resources/application.yml` — `spring.security.oauth2.resourceserver.jwt.*`,
  `audiences: notification`, `suite.app-key`
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/boot/config/...` — register the filters in the right
  order
- `notification/apps/app-boot/src/test/java/dev/ngb/notification/architecture/ArchitectureRules.java` — remove
  `query-side-uses-no-domain-aggregates` from the empty-subject escape hatch now that query classes exist
- `notification/apps/app-boot/build.gradle.kts` — `systemProperty("openapi.update", ...)`

## Implementation Steps

1. Write `NotificationPermissions` with the four constants and a `CODES` set.
2. Copy Billing's `SecurityConfiguration`: stateless chain, permit only `/actuator/health/**`, everything else
   authenticated, `jwt.decoder(decoders)` with `SuiteJwtDecoders`, `jwtAuthenticationConverter(new
   SuiteActorConverter(actorResolver))`, and an `Authorizer` that throws `AccessDeniedException("missing permission
   " + code)`.
3. Copy `TenantContextFilter` and `AppEntitlementFilter`; set the app key to `notification`. Register them in the same
   order Billing does, before the permission interceptor.
4. Copy `ApiExceptionHandler extends CoreExceptionHandlers` so the problem body matches the rest of the suite.
5. Write `TenantScopeAndUserGuard`, a `HandlerInterceptor` registered on `/api/v1/**`: it refuses a `RequestScope`
   that is not a tenant with `AccessDeniedException`, and on the self-service paths it also refuses a caller that is not
   a user. This is the class that makes `@Authenticated` mean what Phase 1 said it means.
6. Write the query classes and their jOOQ repositories. Every query takes the tenant from `TenantContext` and the user
   from `CurrentActor`, never from a parameter. The list response includes `unreadCount` (one `count(*)` filtered by
   `read_at is null`, same tenant and user). Pagination is `where id < :before order by id desc limit :limit`.
7. Write the controllers against the table above. Mark the self-service endpoints with `@Authenticated` and the admin
   endpoints with `@RequiresPermission`; the OpenAPI extensions record `x-allowed-callers: [tenant-user]` on both kinds.
8. Write the five use cases: mark read (404 when the row is not the caller's), mark all read, save preference
   (validate locale in `en|vi`, dedupe `mutedTypes`), save template override (validate key against `NotificationType`,
   refuse forbidden constructs and oversize parts using Phase 4's renderer rules, bump `version`), save tenant settings
   (validate locale and type names).
9. Write `OpenApiContractTest` and generate `contracts/openapi/notification.yaml` with
   `./gradlew :apps:app-boot:test -Dopenapi.update=true`.
10. Write `PermissionCatalogTest`: the constants equal the YAML codes exactly, and every `@RequiresPermission` value
    scanned from compiled classes is one of them.
11. Remove the `query-side-uses-no-domain-aggregates` entry from `RULES_WITHOUT_SUBJECTS` in `ArchitectureTest` and let
    the rule run.
12. Tests: `SuiteTokenSecurityTest` (wrong audience → 401; tenant-system token → 403 on both an admin and a self-service
    endpoint; platform-scope caller → 403 anywhere; missing permission → 403; no entitlement → 403);
    `NotificationApiTest` (own rows only, another user's id → 404, `unreadCount` drops after a read, paging with `before`
    and the `limit` cap, unknown template key → 404, forbidden construct in an override → 400).

## Todo

- [ ] `NotificationPermissions` constants
- [ ] Security configuration, filters, exception handler
- [ ] `TenantScopeAndUserGuard` with the platform-scope and machine-principal refusals
- [ ] Query classes and jOOQ read repositories
- [ ] Twelve endpoints behind the right access rule, `unreadCount` in the list response
- [ ] Five write use cases with validation
- [ ] Generated `contracts/openapi/notification.yaml`
- [ ] Permission catalog and OpenAPI contract tests
- [ ] Query-side rule enabled, escape hatch removed
- [ ] Security and API integration tests

## Success Criteria

- `cd notification && ./gradlew build` passes, including `PermissionCatalogTest`, `OpenApiContractTest` and
  `SuiteTokenSecurityTest`.
- A token minted for another audience returns `401`; a tenant-system token returns `403` on `/api/v1/templates` **and**
  on `/api/v1/notifications/preferences`; a platform-scope user returns `403` on any endpoint; a user without
  `notification:dispatch:read` gets `403` on `/api/v1/email-dispatches`.
- `NotificationApiTest` returns zero rows for user A when user B has notifications in the same tenant, and
  `unreadCount` decrements after a read.
- `contracts/openapi/notification.yaml` is committed and the test fails when the controller changes without
  regeneration.
- `grep -n "query-side-uses-no-domain-aggregates"` finds the rule id only, in no `RULES_WITHOUT_SUBJECTS` entry.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| `PermissionInterceptor` fails closed, so a forgotten annotation breaks every request | The API test exercises every endpoint; the interceptor's failure is loud and immediate |
| `@Authenticated` is mistaken for "tenant user" and lets a machine write a preference row | `TenantScopeAndUserGuard` is a separate, explicitly tested class; the test uses a tenant-system token on a self-service endpoint |
| The entitlement filter passes platform scope, leaving a third door | Every endpoint requires a tenant scope; the test asserts `403` for a platform-scope caller |
| The entitlement filter hides a broken replica behind a `403` | Phase 3's readiness covers an incomplete replica; the API test seeds the entitlement row explicitly |
| Cursor paging duplicates or skips rows under concurrent inserts | Cursor is the UUID v7 id descending with `id < :before`; ids are time-ordered and rows are immutable after insert |
| Template override validation duplicates Phase 4's rules and drifts | The same validation class is called from both the renderer and the use case; the test asserts both refuse the same input |
| OpenAPI drift | Generated file is committed and compared, and the build fails on a mismatch |

## Security Considerations

- Two issuers, one audience: a Billing token or an old bearer is nobody, and a token minted for another app is rejected
  by audience.
- Machine credentials cannot reach the admin endpoints: the codes are not grantable to Tenant Systems.
- Self-service endpoints derive the user from the token, never from a request parameter, so reading someone else's
  notification is not expressible.
- Every read and write is tenant-scoped by the Host-derived context plus RLS.
- Problem bodies never echo a template body or a recipient address; the dispatch endpoint returns the recipient only to
  a caller holding `notification:dispatch:read`.

## Next Steps

Phase 8 wires the app into compose, the proxy, the broker, Terraform and CI, then proves the whole path end to end.
