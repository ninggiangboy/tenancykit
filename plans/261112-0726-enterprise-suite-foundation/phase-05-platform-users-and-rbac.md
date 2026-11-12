---
phase: 5
title: "Platform: users and centralized RBAC"
status: pending
priority: P1
effort: "4d"
dependencies: [4]
---

# Phase 5: Platform users and centralized RBAC

## Goal

Move users, roles, permissions, role assignments, Keycloak identity management and the platform-admin bootstrap from Billing to Platform. Platform manages the roles of every app from the permission catalogs and publishes each user's effective permissions as `user-access.changed`.

## Key insights

- Billing's role model (`billing/contexts/platform/src/domain/java/dev/ngb/billing/platform/domain/role/`) already supports custom roles, direct user permissions and two scopes. It moves almost unchanged; what changes is that permissions are catalog entries (`PermissionCode` value object plus the catalog's `scopes` and `grantable_to_tenant_system`) instead of a Java enum.
- **State transfer per user.** When a role's permissions change, Platform recomputes every user holding that role and emits one `user-access.changed` per user, in the same transaction. Consumers then need no role or join logic; they store one row per user. Tenants are small, so the fan-out is bounded; a role with more than 1,000 holders is split into batches by a job instead of one transaction.
- `ProvisionTenantUseCase` creates a tenant, seeds default roles and the first admin. It depends on tenancy and access, so it lives in `contexts:access` and calls tenancy through its `api`.
- Keycloak keeps authenticating. Platform now holds the realm admin client (today `BILLING_KEYCLOAK_ADMIN_CLIENT_SECRET`). The realm needs one public client for the console with audience mappers for `platform`, `billing` and `notification`, and the existing `tenant_code` user-attribute mapper.

## Files

Create:
- `platform/contexts/access/` with package root `dev.ngb.platform.access`: domain `user` (`User` from `BillingUser`, `UserId`, `UserStatus`), `role` (`Role`, `RoleId`, `AuthorizationPolicy`), `permission` (`PermissionCode`, `PermissionCatalog`); application `user`, `role`, `auth` (`IdentityProviderPort`), `tenant` (`ProvisionTenantUseCase`); adapter `user/persistence`, `role/persistence`, `user/web`, `role/web`, `auth/keycloak/KeycloakIdentityProvider`, `auth/web/MeController`, `catalog/yaml/YamlPermissionCatalog`.
- `platform/apps/app-boot/src/main/resources/db/migration/V2__access.sql`: `permissions`, `users` (from `billing_users`, plus `version`), `roles`, `role_permissions`, `role_assignments`, `user_permissions`, as in `billing/apps/app-boot/src/main/resources/db/migration/V4__identity.sql`, with RLS.
- `platform/apps/app-boot/src/main/java/dev/ngb/platform/boot/startup/PlatformBootstrap.java` (moved from Billing's `boot/startup/PlatformBootstrap.java`), env `PLATFORM_BOOTSTRAP_ADMIN_EMAIL`.
- Snapshot stream `user-access` in `SnapshotController`.
- Gradle task in `platform/apps/app-boot/build.gradle.kts` that copies `../../contracts/permissions/*.yaml` into `build/resources/main/permissions/`.

Modify:
- `ops/keycloak/realm-export.json`: rename the admin client to `platform-admin`, add the `suite-console` public client (PKCE) with audience mappers, keep the `tenant_code` mapper.
- `.env.example`: `PLATFORM_KEYCLOAK_ADMIN_CLIENT_SECRET`, `PLATFORM_BOOTSTRAP_ADMIN_EMAIL`.

Billing is **not** changed in this phase; it still has its own copy until phase 8. Do not run both bootstraps against the same Keycloak user in local dev: document in the phase-8 checklist that Billing's bootstrap env var is removed then.

## API (moved from Billing, same shapes, new permission codes)

`/api/v1/users`, `/api/v1/roles`, `/api/v1/permissions` (lists the catalogs), `/api/v1/me` (user, effective permissions across apps, enabled apps for the tenant), `POST /api/v1/tenants` (provision with optional first admin).

## Implementation steps

1. Write `YamlPermissionCatalog` and `PermissionCode`; `SeedPermissionsUseCase` seeds from the catalog; a test fails on duplicate codes or unknown scopes.
2. Copy user, role and auth code from Billing; replace the `Permission` enum with `PermissionCode` and catalog lookups (`validIn(scope)`, `grantableToTenantSystem`).
3. Add `version` to `User`; add `EffectiveAccess` (application service) that computes a user's permissions from direct grants and role assignments.
4. Emit `user-access.changed` from every write that changes a user's effective permissions: user create, enable, disable, display-name change, role assign or unassign, direct grant or revoke, and role permission edits (fan-out).
5. Move `ProvisionTenantUseCase` and the bootstrap; Keycloak adapter tests with the existing Keycloak test approach (Testcontainers Keycloak if Billing uses it, otherwise the port's fake).
6. `/api/v1/me` for the console.
7. Snapshot stream `user-access` and its test.
8. Integration test: assigning a role to a user publishes `user-access.changed` with the role's codes and the next `version`; editing the role publishes one event per holder.

## Todo

- [ ] Permission catalog loaded from `contracts/permissions/`
- [ ] Users, roles, assignments, direct grants moved; enum replaced
- [ ] Effective access and `user-access.changed` fan-out
- [ ] Provisioning and bootstrap moved; Keycloak realm updated
- [ ] `/api/v1/me`, `user-access` snapshot stream
- [ ] Tests ported and new tests added

## Verification

- `cd platform && ./gradlew build` green.
- Manually against compose Keycloak: the bootstrap admin logs in through `suite-console` and `GET /api/v1/me` lists `platform:*` permissions.

## Risks

- Fan-out size on a widely held role. Mitigation: batch threshold described above and a test with 1,500 holders.
- Two bootstraps creating the same Keycloak user during the transition. Mitigation: only Platform's bootstrap env var is set in `.env.example` from this phase on.

## Security

- A role can hold only codes whose catalog `scopes` include the role's scope; a tenant role can never hold `platform:*` codes that are platform-only.
- Users still identify by `{tenant-code}:{email}` usernames in Keycloak; the token's `tenant_code` must match the host's tenant, exactly as in `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/security/JwtActorConverter.java`.
