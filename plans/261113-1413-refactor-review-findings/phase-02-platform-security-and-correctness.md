---
phase: 2
title: "Platform security and correctness"
status: pending
priority: P1
effort: M
dependencies: [1]
---

# Phase 2: Platform security and correctness

## Goal

Close the privilege-escalation and credential-race paths, move network calls out of database transactions, make access
changes auditable, and stop the token endpoint from being brute-forceable.

## Findings addressed

- `access/adapter/role/web/RoleController.java:64` takes `tenantId` and `scope` from the request body;
  `CreateRoleUseCase:34-41` validates codes against the requested scope, never the caller's; `Authorizer` checks only
  `actor.has(code)`. A Tenant Admin (seeded with every tenant-scope code) can mint a `PLATFORM` role and assign it.
- `ManageCredentialUseCase:98-124` reads without a lock, `ApiCredential` has no `version`, and
  `JooqCredentialRepository.update` has no version guard: concurrent rotations create three live credentials, and a
  rotate can overwrite a revocation.
- `CreateUserUseCase:40-48` calls Keycloak inside `unitOfWork.transactional`, with no compensation.
- `IssueTokenUseCase:83-92` acquires the rate-limit bucket after checking the secret and spreads anonymous attempts
  over 1024 client-id buckets.
- `contexts/access` writes no `AuditLog` entry, while tenancy and integration audit every command.
- `TenantController:68` guards rename with `platform:tenant:create`; no rename code exists.
- `TenantSystemController:172-182` performs the credential-ownership check in the controller.
- `boot/security/ServiceClients.java` is a dead copy of `ConfiguredServiceClients`.
- `adapter/token/web/PostgresRateLimiter.java` is a persistence adapter in a `web` package.
- `ProvisionTenantUseCase` uses four independent transactions.

## Files to create / modify

- `platform/contexts/access/src/application/.../role/command/CreateRoleUseCase.java`,
  `.../role/command/ManageRoleUseCase.java`, `.../user/command/CreateUserUseCase.java`,
  `.../user/command/ManageUserPermissionsUseCase.java`: accept the acting `Actor` (or `CurrentActor`), derive
  `tenantId`/`scope`, and reject a role wider than the caller's scope.
- `platform/apps/app-boot/src/main/java/dev/ngb/platform/boot/security/SecurityConfiguration.java` and
  `.../Authorizer` implementation: make `require(code)` verify both the code and its scope against the actor.
- `platform/contexts/access/src/adapter/.../role/web/RoleController.java`,
  `.../user/web/UserController.java`: remove `tenantId`/`scope` from request bodies or ignore them; keep the OpenAPI
  schema in sync.
- `platform/contexts/integration/src/domain/.../credential/ApiCredential.java`: add `version`.
- `platform/contexts/integration/src/application/.../credential/command/ManageCredentialUseCase.java`,
  `.../credential/port/CredentialRepository.java`,
  `.../adapter/.../credential/persistence/JooqCredentialRepository.java`: `lockByTenantSystem`, guarded updates,
  rotate/revoke under one lock.
- `platform/apps/app-boot/src/main/resources/db/migration/V4__integration_credential_version.sql`: add
  `api_credentials.version bigint not null default 1`; regenerate `platform/libs/jooq-schema`.
- `platform/contexts/access/src/application/.../user/command/CreateUserUseCase.java`: call Keycloak outside the unit
  of work; persist inside it; delete the identity on insert failure.
- `platform/contexts/integration/src/application/.../token/command/IssueTokenUseCase.java` and
  `.../adapter/token/web/PostgresRateLimiter.java`: acquire before digest verification; one global bucket plus
  per-client and per-IP; move the limiter to `adapter/token/persistence`.
- `platform/contexts/access/src/application/.../role/command/*`, `.../user/command/*`,
  `.../access/command/*`: inject `AuditLog`, `IdGenerator`, `Clock`, and record create/rename/replace/assign/grant/
  disable/enable.
- `contracts/permissions/platform.yaml`: add `platform:tenant:rename`; `TenantController` uses it.
- `platform/contexts/integration/src/application/.../credential/command/ManageCredentialUseCase.java`: ownership check
  moves from the controller into `revoke(...)`.
- Delete `platform/apps/app-boot/.../security/ServiceClients.java`; point the test at
  `ConfiguredServiceClients.sha256`.
- `platform/contexts/access/src/application/.../tenant/command/ProvisionTenantUseCase.java`: one unit of work around
  the whole provisioning, or explicit resumable/idempotent steps.

## Tasks

1. Add a failing test first: tenant admin creates a `PLATFORM` role with `platform:entitlement:manage`; assert 403.
2. Make the authorizer scope-aware and thread the acting actor through role/user commands.
3. Add `ApiCredential.version`, migration, jOOQ regeneration, `lockByTenantSystem`, guarded update, and a race test
   (rotate × revoke, rotate × rotate).
4. Move the Keycloak call outside the transaction; add a compensation test using a failing repository insert.
5. Reorder the token rate limit and collapse the anonymous bucket; add a test that N+1 guesses with different client
   ids still hits one budget.
6. Add audit entries to every access command; assert one entry per action in an integration test.
7. Add `platform:tenant:rename` and switch the controller; run the permission-catalog test.
8. Move the revoke ownership check into the use case and the rate limiter into the persistence package.
9. Delete the dead `ServiceClients`; make provisioning atomic or document its resumability.

## Verification

- `cd platform && ./gradlew build`
- `cd platform && ./gradlew :apps:app-boot:test --tests '*Role*IntegrationTest' --tests '*Credential*IntegrationTest' --tests '*Token*IntegrationTest' --tests '*Provision*'`
- `cd platform && ./gradlew :apps:app-boot:test --tests '*ArchitectureTest' --tests '*PermissionCatalogTest'`

## Success criteria

- [x] A tenant administrator cannot create or assign a role wider than their own scope; the escalation test passes.
- [x] Concurrent rotate/revoke leaves at most two live credentials and never resurrects a revoked one.
- [x] A failed user insert leaves no Keycloak identity, and no HTTP call runs inside a transaction.
- [x] Token guesses are throttled before secret comparison and share one anonymous budget.
- [x] Every access command writes exactly one audit entry.
- [x] The permission catalog is the only list; `platform:tenant:rename` exists.

## Risks

The authorizer change can lock out a legitimate platform administrator. Mitigation: a test matrix for platform admin,
tenant admin, tenant system and service client before merging; rollout note in `docs/authentication-and-authorization.md`.

## Notes from the remaining work

- `ProvisionTenantUseCase` takes neither of the two options as stated: the Keycloak identity is created before the
  unit of work (so no HTTP call runs inside a transaction) and everything the database sees — Tenant, seeded role,
  administrator, assignment — runs in one unit of work, with the identity deleted again when it fails. That is
  atomic for the database and idempotent from the caller's point of view, and it keeps the "no HTTP call inside a
  transaction" rule that `CreateUserUseCase` follows.
- The one shared anonymous rate-limit bucket needs its own, wider allowance (`platform.security.token-rate-limit.anonymous-burst`).
  With the per-client allowance it would only duplicate the per-client bucket and let one source exhaust the
  endpoint's backstop for every other caller. The per-client and per-caller-address buckets keep the configured
  `burst`.
- The caller address comes from `HttpServletRequest.getRemoteAddr()`. Behind a proxy, set Spring Boot's
  `server.forward-headers-strategy` so the address is the client's, not the proxy's.
