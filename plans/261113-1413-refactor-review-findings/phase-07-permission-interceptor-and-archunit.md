---
phase: 7
title: "Permission interceptor and shared ArchUnit rules"
status: pending
priority: P1
effort: M
dependencies: [1, 2, 4]
---

# Phase 7: Permission interceptor and shared ArchUnit rules

## Goal

One fail-closed permission interceptor, one shared architecture rule set with proof fixtures, and every module
exposing its contract through `api` or documenting the exception.

## Findings addressed

- Three interceptors: `core-spring` (fail-closed), billing's fork (adds `PortalAuthorizer`), tenant's fork (annotation
  with an enum, **fail-open** when no annotation is present).
- Architecture rule counts: billing 37, tenant 28, platform 19. Platform lacks
  `command-package-holds-only-use-cases`, `application-classes-in-a-role-package`,
  `repository-pair-shares-a-package`, read/write separation, `api-implementations-are-services`, and others. Tenant
  lacks the role/port/repository rules. This is why known drift passes.
- `EntitlementRows` is a non-`*UseCase` class in a `command` package (platform has no such rule).
- Six modules have no `api` source set: `billing/contexts/{invoicing,webhook}`, `platform/contexts/{access,integration}`,
  `tenant/modules/{execution,webhook}`; `JwtActorConverter` and `SnapshotController` bind to `application...query`.
- `PostgresRateLimiter` is a persistence adapter in a `web` package; no rule forbids it.
- Tests in tenant do not mirror production packages (`.../application/Fakes.java`, `.../application/*UseCaseTest.java`)
  while docs say they must; `ArchitectureTest` excludes tests.

## Files to create / modify

- Create `core/core-arch/build.gradle.kts` and `core/core-arch/src/main/java/dev/ngb/core/arch/ArchitectureRules.java`
  (generic rules parameterised by root package, table-owner map and allowed query types). Add `archunit-junit5` as
  `api`; add the module to `core/settings.gradle.kts`.
- Delete the rule bodies from `billing/apps/app-boot/src/test/java/dev/ngb/billing/architecture/ArchitectureRules.java`,
  `platform/apps/app-boot/src/test/java/dev/ngb/platform/architecture/ArchitectureRules.java`,
  `tenant/apps/app-boot/src/test/java/dev/ngb/tenant/architecture/ArchitectureRules.java`; each keeps only its
  profile (`root`, table owners, extra rules).
- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/web/PermissionInterceptor.java`: delete; extend the core
  interceptor or add a `PermissionCheck` chain.
- `platform/apps/app-boot/.../web/WebConfiguration.java`: keep using the core interceptor.
- `tenant/apps/app-boot/.../web/PermissionInterceptor.java`: delete; tenant uses `core.security.RequiresPermission` +
  `Authorizer`; add an adapter from `Permission` enum to code.
- `tenant/apps/app-boot/.../security/SecurityConfiguration.java`: keep `anyRequest().authenticated()`.
- `tenant/modules/*/src/adapter/**/*Controller.java`: add `@RequiresPermission` or `@Authenticated`/`@PublicEndpoint`
  to every endpoint, especially `CatalogController`.
- Create the missing `api` packages and `package-info.java`: `billing/contexts/invoicing/src/api`,
  `billing/contexts/webhook/src/api`, `platform/contexts/access/src/api`,
  `platform/contexts/integration/src/api`, `tenant/modules/execution/src/api`,
  `tenant/modules/webhook/src/api`; move `MeQueries`/snapshot query types used from boot behind them.
- `platform/contexts/integration/src/adapter/.../token/web/PostgresRateLimiter.java`: move to
  `adapter/.../token/persistence/`.
- `platform/contexts/tenancy/src/application/.../entitlement/command/EntitlementRows.java`: move to `service`.
- Add the missing rules to the shared set: `command-package-holds-only-use-cases`, `application-classes-in-a-role-package`,
  `port-package-holds-no-implementations`, `repository-pair-shares-a-package`, `api-implementations-are-services`,
  `write-side-does-not-depend-on-query`, `read-side-does-not-depend-on-write`, `query-side-uses-no-domain-aggregates`,
  `write-ports-serve-no-reads`, `use-case-opens-a-unit-of-work`, `web-package-holds-no-persistence`,
  `tests-mirror-production-packages`.
- Extend `*/testing/arch-fixtures` with a violating fixture for every new rule and keep `ArchitectureMetaTest`.

## Tasks

1. Extract the rule framework into `core-arch`; prove it with the existing fixtures before deleting the copies.
2. Add a CI check that every service runs the same rule IDs (a test comparing the resolved list or a generated
   `rules.txt` artifact).
3. Replace tenant's interceptor with the core annotations and authorizer; prove an annotated endpoint and an
   unannotated endpoint behave fail-closed.
4. Add the missing annotations to tenant controllers; remove unused `Permission` values.
5. Create the `api` packages and move the query types boot imports.
6. Move `PostgresRateLimiter` and `EntitlementRows`; add the two rules and their fixtures.
7. Move tenant tests into mirrored packages and enable the test-package rule.

## Verification

- `cd core && ./gradlew :core-arch:build`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*ArchitectureTest' --tests '*ArchitectureMetaTest'`
- `cd platform && ./gradlew :apps:app-boot:test --tests '*ArchitectureTest' --tests '*ArchitectureMetaTest'`
- `cd tenant && ./gradlew :apps:app-boot:test --tests '*ArchitectureTest' --tests '*ArchitectureMetaTest'`
- `cd billing && ./gradlew build && cd ../platform && ./gradlew build && cd ../tenant && ./gradlew build`

## Success criteria

- [ ] One generic rule implementation exists; the three services run the same rule IDs plus documented extras.
- [ ] Every rule fails on its fixture; the meta test lists every rule.
- [ ] Every tenant endpoint declares an access rule; an unannotated endpoint fails closed.
- [ ] Six modules have an `api` package or a documented exception in `docs/architecture.md`.
- [ ] `web` packages contain no jOOQ/JdbcTemplate type; `command` packages contain only `*UseCase` (or the helper has
      moved to `service`).

## Risks

The shared rule set will surface existing violations. Mitigation: phases 2–6 fix the known ones first; land the
shared rules with the fixtures and a temporary, documented allowlist only for violations with a dated follow-up.
