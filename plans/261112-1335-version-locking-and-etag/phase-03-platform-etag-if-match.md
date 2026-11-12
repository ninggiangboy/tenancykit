---
phase: 3
title: "Platform ETag and If-Match"
status: done
priority: P2
effort: "0.5d"
dependencies: [1, 2]
---

# Phase 3: Platform ETag and If-Match

## Goal

The in-scope Platform update endpoints accept an optional `If-Match`, check it under the row lock from phase 1, and
return the new `ETag`. The single-resource GET endpoint returns an `ETag` too.

## Endpoints

| Endpoint | Version checked | Use case |
|---|---|---|
| `PUT /api/v1/tenants/{id}` | tenant | `RenameTenantUseCase` |
| `POST /api/v1/tenants/{id}/deactivate` | tenant | `DeactivateTenantUseCase` |
| `PUT /api/v1/roles/{id}` | role | `ManageRoleUseCase` (RENAME) |
| `PUT /api/v1/roles/{id}/permissions` | role | `ManageRoleUseCase` (REPLACE_PERMISSIONS) |
| `PATCH /api/v1/users/{id}` | user | `ManageUserUseCase` |
| `PUT /api/v1/users/{id}/permissions/{code}` | user | `ManageUserPermissionsUseCase` |
| `PUT /api/v1/tenant-systems/{id}` | tenant system | `ManageTenantSystemUseCase` |
| `PUT /api/v1/tenants/{tenantId}/apps/{appCode}` | entitlement | `EnableAppUseCase` / `DisableAppUseCase` |
| `GET /api/v1/tenant-systems/{id}` | ETag only | the existing query |

Tenants, roles and users have list GETs only, so their responses already carry `version` in the body. A client sends
`If-Match: "<version>"` taken from the list item. Do not add new single-resource GETs; that is out of scope.

## Steps

1. Add `ExpectedVersion expected` as the last field of each listed use case's `Command`. Existing internal callers pass
   `ExpectedVersion.any()`.
2. In each use case, call `command.expected().check(aggregate.version())` immediately after the lock from phase 1 and
   before any mutation. A no-op change (for example, renaming to the same name) still checks first, so a stale tag is
   never answered with 200. For entitlements, call `checkAbsent()` when the row does not exist yet.
3. In each controller:
   - Add `@RequestHeader(value = "If-Match", required = false) String ifMatch`.
   - Pass `ExpectedVersion.fromIfMatch(ifMatch)`.
   - Return `ResponseEntity.ok().eTag(ExpectedVersion.etag(result.version())).body(...)`. Spring's `eTag(...)` quotes
     an unquoted value itself, so pass the already-quoted string or the raw number, consistently.
4. The `version` in the response equals the version after the whole transaction. `ManageUserUseCase` bumps twice
   (change plus `publishFor`), so read the version from the returned object, not from the command.

## Tests

`platform/apps/app-boot/src/test/java/dev/ngb/platform/web/ConditionalUpdateHttpIntegrationTest.java` (MockMvc, real
Postgres). For each endpoint:

- No `If-Match`: 200 plus an `ETag`.
- Current tag: 200 plus a new `ETag` (n+1).
- Stale tag: 412, the current `ETag` in the response headers, and the row unchanged (no outbox row, no audit row).
- `*`: 200.
- `W/"n"`: 412.
- Malformed: 400.

Entitlement only: `If-Match` on a row that does not exist gives 412.

## Verification

- `cd platform && ./gradlew :apps:app-boot:test --tests '*ConditionalUpdate*'`
- `cd platform && ./gradlew build`

## Risks

- A spurious 412 on a user whose role changed. This is by design and documented in phase 5.
