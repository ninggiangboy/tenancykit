---
title: "Phase 3: UI remote registry backend"
status: completed
priority: P1
effort: "4d"
dependencies: [1]
---

# Phase 3: UI remote registry backend

## Goal

Platform stores UI remotes and their versions, lets CI register a version, lets an operator enable, disable and
re-point the active version, and answers the shell with only the remotes its caller may use. It also exposes the app
entitlements of a tenant for reading, which the tenants screen needs and Platform lacks today.

## Context

- Home: the `tenancy` context (`platform/contexts/tenancy`), feature package `uiremote`, beside `entitlement`; it joins
  entitlements and needs no new Gradle module. Layering follows `docs/architecture.md`: `domain`, `application`
  (`command`, `query`, `port`, `exception`), `adapter` (`persistence`, `web`); tests mirror the production packages.
- Global tables: `V1__baseline.sql` grants `platform_app` on every table by default privilege, and `tenants` is a
  plain table readable without a tenant context. The registry holds no tenant data, so its tables follow `tenants`
  (no `tenant_id`, no `enable_tenant_rls`). The feed query reads `app_entitlements` for the caller's tenant under the
  tenant context the request already binds; read how `app_entitlements` is protected before writing it.
- Service clients are `application.yml` entries; the token carries only configured scopes
  (`contracts/tokens/README.md`).
- Every list endpoint uses the list-query contract (`docs/list-query-contract.md`).

## Data model (migration `V7__tenancy_ui_remote_registry.sql`)

- `ui_remotes`: `name` (pk, `^[a-z][a-z0-9-]{1,30}$`), `app_code`, `title`, `nav_order`, `audiences text[]` (values
  `PLATFORM`, `TENANT`, at least one), `enabled boolean`, `active_version text null` (references a version row),
  `created_at`, `version bigint` (optimistic).
- `ui_remote_versions`: `(remote_name, version)` pk, `entry_path`, `integrity` (`sha384-` plus base64), `sdk_major int >= 1`,
  `registered_at`, `registered_by`. Rows are immutable.

## API (all under `/api/v1`)

| Operation | Permission | Rule |
|---|---|---|
| `POST /ui-remotes/{name}/versions` body `{version, entryPath, integrity, sdkMajor, appCode?, title?, audiences?, navOrder?}` | `platform:ui-remote:register` (service client only, not grantable to a tenant system) | Creates the remote on first call (needs `appCode`, `title`, `audiences`). `entryPath` must match `^/console/remotes/<name>/<version>/[A-Za-z0-9._-]+$`. Same version with the same content is `200` idempotent; same version with different content is `409`. Registering never changes the active version; a new remote starts disabled with no active version until an operator acts. |
| `PUT /ui-remotes/{name}` body `{enabled, activeVersion?, title?, navOrder?}` (activating a version is this call) | `platform:ui-remote:manage` (platform scope) | `If-Match` on `version`; `activeVersion` must be a registered version. |
| `GET /ui-remotes` (list-query: filter `name`, `app_code`, `enabled`; sort `name`, `nav_order`) | `platform:ui-remote:read` (platform scope) | Includes each remote's versions and the active one. |
| `GET /console/remotes` | any authenticated user | Returns `{name, title, appCode, navOrder, version, entryPath, integrity, sdkMajor}` for remotes that are enabled, have an active version, list the caller's audience (platform scope: `PLATFORM`, tenant scope: `TENANT`) and, unless `appCode` is `platform`, whose app is enabled for the caller's tenant. |
| `GET /tenants/{tenantId}/apps` | `platform:tenant:read` | Lists the tenant's app entitlements `{appCode, enabled, version}`; new, because only `PUT` exists today. |

## Files

- Create: `platform/apps/app-boot/src/main/resources/db/migration/V7__tenancy_ui_remote_registry.sql`.
- Create under `platform/contexts/tenancy/src/`: `domain/.../domain/uiremote/{UiRemote,UiRemoteVersion,RemoteAudience}.java`;
  `application/.../application/uiremote/command/{RegisterUiRemoteVersionUseCase,SetUiRemoteStateUseCase}.java`;
  `application/.../application/uiremote/query/{UiRemoteQueries,ConsoleRemoteQueries,UiRemoteView,ConsoleRemoteView}.java`;
  `application/.../application/uiremote/port/{UiRemoteRepository,UiRemoteReadRepository}.java`;
  `application/.../application/uiremote/exception/` (version conflict, unknown remote, bad entry path);
  `adapter/.../adapter/uiremote/persistence/{JooqUiRemoteRepository,JooqUiRemoteReadRepository}.java`;
  `adapter/.../adapter/uiremote/web/{UiRemoteController,ConsoleRemoteController}.java`; the entitlement read in
  `application/.../entitlement/query` and `adapter/.../entitlement/web/EntitlementController.java` (modify).
- Create tests mirroring those packages.
- Modify: `contracts/permissions/platform.yaml` (add `platform:ui-remote:register`, `platform:ui-remote:manage`,
  `platform:ui-remote:read`), `platform/libs/shared-kernel/.../PlatformPermissions.java`,
  `platform/apps/app-boot/src/main/resources/application.yml` (service client `frontend-ci`,
  `PLATFORM_FRONTEND_CI_SERVICE_CLIENT_ID` and `..._SECRET_HASH`, scope `platform:ui-remote:register`), `docker-compose.yml`
  and `.env.example` (the two variables), `platform/libs/jooq-schema` (regenerated, committed),
  `contracts/openapi/platform.yaml` (regenerated).

## Steps

1. Read `V1__baseline.sql`, `V3__integration.sql`, the `entitlement` feature and `TenantProvisioningService` to copy
   naming, audit and optimistic-version conventions; read how list-query schemas are declared in `core-query` for an
   existing list.
2. Write the migration; back up the dev database before applying (project rule) and apply through Flyway only.
3. Regenerate jOOQ using the repo's documented command; commit the generated module.
4. Domain first: `UiRemote.activate(version)` (only the operator path calls it), immutability and idempotency of a version, the entry-path and
   integrity checks. Unit-test them.
5. Use cases and repositories; the console feed is one read query joining `ui_remotes`, `app_entitlements` and the
   caller's scope and tenant; no per-request call to anything else.
6. Controllers with `@RequiresPermission` and `If-Match`; reuse `CurrentActor` for `registered_by`.
7. Add the permissions, the `frontend-ci` service client, compose variables; regenerate and review the OpenAPI document.
8. Integration tests (Testcontainers): register idempotently and with a conflict; bad entry path `400`; activate and
   disable; feed filtered by tenant entitlement on and off, by audience, by disabled and by no active version;
   token tests: `frontend-ci` token registers, a tenant-system token and a user without the permission are refused.

## Todo

- [x] Migration and regenerated jOOQ
- [x] Domain rules and unit tests
- [x] Register and manage use cases, repositories
- [x] Console feed query and controller
- [x] Entitlement read endpoint
- [x] Permissions, `frontend-ci` service client, compose and env
- [x] OpenAPI regenerated and contract test green
- [x] Integration tests green, ArchUnit green

## Verification

```bash
cd platform && ./gradlew build
cd platform && ./gradlew :apps:app-boot:test --tests '*PlatformOpenApiContractTest'
```

Both exit 0; `git diff contracts/openapi/platform.yaml` shows only the new operations.

## Risks

- The feed is the one endpoint every console page load calls: keep it a single indexed query and add an index on
  `app_entitlements (tenant_id, app_code)` only if the plan shows a scan.
- `frontend-ci` is a new long-lived secret: store only the hash, give it the single scope, and document rotation in
  `docs/platform-service.md` in phase 14.

## Security

The registry decides what script the browser runs. Writes need the `register` scope or the `manage` permission; the
entry path is allow-listed in the domain, not only in the controller; versions are immutable so a registered build
cannot be swapped under an integrity hash; `registered_by` is audited.
