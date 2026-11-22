---
phase: 7
title: "Platform admin split and me routes"
status: pending
priority: P1
effort: 3d
dependencies: [6]
---

# Phase 7: Platform admin split and me routes

## Goal
Platform and Notification separate platform-only, tenant-only, shared and self-service operations by prefix, and the
consoles follow.

## Route changes
| Today | After | Caller |
|---|---|---|
| `/api/v1/tenants/**`, `/tenants/{id}/domains`, `/tenants/{id}/apps/**`, `/api/v1/ui-remotes/**` | `/admin/v1/...` | platform-user |
| `POST /api/v1/ui-remotes/{name}/versions` | `/admin/v1/ui-remotes/{name}/versions` | internal (`frontend-ci`) |
| `/api/v1/users/**`, `/api/v1/roles/**` | `/admin/v1/users`, `/admin/v1/roles` (all Tenants) and `/api/v1/users`, `/api/v1/roles` (own Tenant) | platform-user / tenant-user |
| `/api/v1/tenant-systems/**` (incl. credentials) | `/api/v1/...` unchanged | tenant-user |
| `GET /api/v1/me`, `/api/v1/console/remotes` | `/api/v1/me`, `/api/v1/me/console-remotes` | platform-user, tenant-user |
| Notification `/api/v1/notifications/**`, `/preferences` | `/api/v1/me/notifications`, `/me/notifications/{id}/read`, `/me/notifications/read-all`, `/me/notification-preferences` | platform-user, tenant-user |
| Notification `/api/v1/templates/**` | `/admin/v1/templates/**` | platform-user |
| Notification `/api/v1/settings`, `/email-dispatches` | unchanged | tenant-user |
| `GET /internal/replica/{stream}` | `/internal/v1/replica/{stream}` | internal |
| `/oauth/token`, `/.well-known/jwks.json` | unchanged | exceptions |

## Files to Create / Modify
- Platform controllers also drop their literal `/api/v1` and take their prefix from `@Callers`; the platform-only
  ones carry `@Callers(PLATFORM_USER)` (so `/tenants` is served at `/admin/v1/tenants`), `tenant-systems` and
  `credentials` carry `@Callers(TENANT_USER)`, the snapshot controller `@Callers(INTERNAL)`, `me` and console remotes
  `@SelfService`, and `/oauth/token` and jwks `prefix = NONE`
- Split (thin controllers over the same use cases; each pins the scope and answers `403` for the wrong one):
  `backend/services/platform/contexts/access/src/adapter/java/dev/ngb/platform/access/adapter/user/web/UserController.java`
  into `AdminUserController` and `TenantUserController`; `.../role/web/RoleController.java` into `AdminRoleController`
  and `TenantRoleController`
- Modify: the Platform controllers for tenants, domains, entitlements, ui-remotes (and its registration endpoint),
  `/me`, console remotes; `backend/services/platform/apps/app-boot/src/main/java/dev/ngb/platform/boot/replica/SnapshotController.java`
  and `.../boot/security/SecurityConfiguration.java`; `@Callers` on `/oauth/token`, jwks and the registration endpoint
- Modify: every `SnapshotFeignApi` (Notification, Booking, Mailroom, Loyalty, Workflow and the others found in phase
  1) and the `core-feign` / `core-platform-sync` tests that name `/internal/replica`
- Modify: Notification controllers (`/me`, `/admin/v1/templates`)
- Modify: `contracts/openapi/platform.yaml`, `notification.yaml` and the per-caller files; `contracts/tokens/README.md`
- Modify (frontend): regenerate `frontend/packages/api-client/src/generated/**` from the contracts (command from phase
  1), `frontend/apps/platform-shell`, `frontend/apps/tenant-shell`, `frontend/remotes/**`,
  `frontend/packages/shell-core` (`boot.test.ts` references `/api/v1/console`), `frontend/e2e/support/platform.ts`,
  `stack.ts`
- Modify: the frontend-CI registration script that posts a remote version (path in the console deploy scripts under
  `ops/console/`)

## Tasks & Steps
1. Split users and roles first (tests prove: an administrator on `/admin/v1/users` sees every Tenant and may create a
   platform-scope user; a tenant user on `/api/v1/users` sees only their Tenant and cannot; each wrong-scope call is
   403).
2. Move tenants, domains, entitlements and ui-remotes to `/admin/v1`; move the registration endpoint and update
   `ops/console` deploy scripts.
3. Move `me` and console remotes; move Notification's inbox and preferences under `/me`; move templates to `/admin/v1`.
4. Move the snapshot endpoint and every client of it in one change so replica rebuild never breaks.
5. Regenerate contracts and the frontend client; fix the shells and e2e; run the full local stack.

## Verification
- `./gradlew test` in `backend/services/platform` and `backend/services/infra/notification`.
- `pnpm -C frontend typecheck test` and `pnpm -C frontend e2e`.
- Replica rebuild check: wipe one app's replica tables in compose, restart its worker, the replica repopulates.

## Risks
- A use case behind users or roles reads the host to decide scope: stop and ask (phase 1 found the reach comes from
  the actor).
- Console remotes already in a registry call old paths; no environment is live, so republish them.
