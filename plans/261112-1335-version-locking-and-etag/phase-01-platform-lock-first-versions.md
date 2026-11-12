---
phase: 1
title: "Platform lock-first version bumps"
status: done
priority: P1
effort: "1d"
dependencies: []
---

# Phase 1: Platform lock-first version bumps

## Goal

Every Platform use case that bumps a `version` holds the row lock across read, bump and write. As a result, two
committed events for one row never share a version, and every published state reflects every committed change.

## Lock order (new, Platform only)

| Flow | Locks in order |
|---|---|
| Role rename / replace permissions | role `for no key update`, then each user holding the role, ascending by id, `for update` |
| Role assign / unassign | role `for share`, then the user `for update`, then the assignment row is written |
| User patch, direct permission grant, `publishFor` | the user `for update` |
| Tenant rename / deactivate | tenant `for no key update` |
| Tenant system update | tenant system `for no key update` |
| Entitlement enable / disable | entitlement row `for update`. When absent: `insert ... on conflict do nothing`, then lock it |

Why this order is safe:

- `for share` on the role conflicts with `for no key update`. An assign and a replace-permissions on one role therefore
  serialise, and whichever runs second sees the other's committed rows.
- Users are always locked after roles. A fan-out always locks users in ascending id order. So no flow can form a wait
  cycle.
- `no key update` (not `for update`) keeps the FK `key share` taken by inserts into child tables from blocking on a
  rename.

## Files to modify

- `platform/contexts/access/src/application/java/dev/ngb/platform/access/application/user/port/UserRepository.java`:
  add `Optional<User> lockById(UserId)`.
- `platform/contexts/access/src/adapter/java/dev/ngb/platform/access/adapter/user/persistence/JooqUserRepository.java`:
  - Implement `lockById` with `forUpdate()`.
  - Guard `update` with `and version < :newVersion`, and throw `IllegalStateException` when it updates 0 rows. This is
    defence in depth: a future unlocked path fails loudly instead of publishing a duplicate version.
- `platform/contexts/access/src/application/java/dev/ngb/platform/access/application/role/port/RoleRepository.java`:
  add `lockForUpdate(RoleId)` and `lockForShare(RoleId)`.
- `platform/contexts/access/src/adapter/java/dev/ngb/platform/access/adapter/role/persistence/JooqRoleRepository.java`:
  implement them with `forNoKeyUpdate()` and `forShare()`, and add the same guarded `update`.
- `.../access/application/access/service/AccessPublisher.java`:
  - `publishFor(UserId)` locks the user, bumps, updates, then computes `effectivePermissions`. The computation stays
    after the lock and the update, so READ COMMITTED gives it a fresh snapshot.
  - `publishForRole` iterates `userIdsForRole` sorted ascending.
  - Keep a `publishFor(User)` overload only for callers that already hold the lock (`ManageUserUseCase`).
- `.../access/application/access/port/AccessRepository.java` and its jOOQ adapter: `userIdsForRole` returns ids
  `order by user_id`.
- `.../access/application/role/command/ManageRoleUseCase.java`:
  - Rename and replace use `lockForUpdate`.
  - Assign and unassign use `lockForShare` on the role, then `users.lockById` before `access.assign` / `unassign`.
- `.../access/application/user/command/ManageUserUseCase.java` and `ManageUserPermissionsUseCase.java`: use
  `users.lockById`.
- `ProvisionTenantUseCase.java` and `BootstrapPlatformAdminUseCase.java`: switch to `publishFor(UserId)` or the locked
  overload. They create the user in the same transaction, so this needs no extra lock order.
- `platform/contexts/tenancy/.../tenant/port/TenantRepository.java` and `JooqTenantRepository.java`: add
  `lockById(TenantId)` (`forNoKeyUpdate()`) and the guarded `update`.
- `RenameTenantUseCase.java` and `DeactivateTenantUseCase.java`: use `lockById`.
- `platform/contexts/integration/.../tenantsystem/port/TenantSystemRepository.java` and `JooqTenantSystemRepository.java`:
  add `lockById` and the guarded `update`. `ManageTenantSystemUseCase.java` uses it.
- `platform/contexts/tenancy/.../entitlement/port/AppEntitlementRepository.java` and `JooqEntitlementRepository.java`:
  - Add `lock(TenantId, appCode)` (`forUpdate()`) and `insertIfAbsent(AppEntitlement)` (`on conflict do nothing`).
  - Replace the blind upsert with a guarded `update`.
- `EnableAppUseCase.java` and `DisableAppUseCase.java`: lock the row. When absent, `insertIfAbsent`, then lock and
  apply. The first enable creates at version 1. A concurrent first enable inserts nothing and then bumps to 2 under the
  lock.
- `platform/apps/app-boot/src/main/java/dev/ngb/platform/boot/web/ApiExceptionHandler.java`: map
  `PessimisticLockingFailureException` to 503 with `Retry-After: 1`, as Billing's handler does. Without it, a lock
  timeout is an opaque 500.
- `platform/apps/app-boot/src/main/resources/application.yml` (Platform's only profile file): set
  `hikari.connection-init-sql: "SET lock_timeout = '10s'; SET idle_in_transaction_session_timeout = '60s'"`. Platform
  has no lock timeout today, so a stuck holder would block admin requests indefinitely.

## Tests (create)

All under `platform/apps/app-boot/src/test/java/dev/ngb/platform/access/` unless noted. Copy Billing's
`billing/apps/app-boot/src/test/java/dev/ngb/billing/support/UncommittedRace.java` into the Platform test sources (or
into a shared test fixture, if one exists by then). It holds the first transaction open until the second is blocked.

1. `ConcurrentUserAccessIntegrationTest`:
   - Assign role X and role Y to one user concurrently.
   - Assert: the two `user-access.changed` outbox rows have distinct versions, and the higher version holds both
     permissions.
   - Apply both events to a `ReplicaWriter` over the test schema, in both orders. The resulting grant equals
     `effectivePermissions` both times.
2. The same test class, an unassign racing an assign: the revoked permission is absent from the final replica row in
   both delivery orders.
3. `RolePermissionRaceIntegrationTest`: assign R to U while replacing R's permissions. The final published state of U
   contains R's new permissions.
4. `platform/apps/app-boot/src/test/java/dev/ngb/platform/tenancy/EntitlementRaceIntegrationTest`: a concurrent first
   enable and disable on one (tenant, app) gives versions 1 and 2. The replica ends with the state of version 2.
5. A repository test: the guarded `update` with a stale version throws.

## Verification

- `cd platform && ./gradlew :apps:app-boot:test --tests '*Concurrent*' --tests '*Race*'`
- `cd platform && ./gradlew build` (includes ArchUnit and `JooqSchemaTest`; no schema change in this phase)
- Run Billing's deadlock stress test unchanged, to confirm that the replica path still applies cleanly:
  `cd billing && ./gradlew :apps:app-boot:test --tests '*DeadlockStress*'`

## Risks

- Any missed `findById` → `update` path keeps the bug. Mitigation: the guarded `update` throws on a non-increasing
  version, and grepping `\.update\(` in `platform/contexts` must list only locked call sites (listed above).
- A role with many users makes replace-permissions lock many user rows. This is acceptable for admin traffic; the 10s
  lock timeout bounds the wait.

## Rollback

Code-only, with no migration. Revert the commit.
