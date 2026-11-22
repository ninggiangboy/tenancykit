---
phase: 5
title: "Prefix mounting and token enforcement"
status: pending
priority: P1
effort: 3d
dependencies: [3]
---

# Phase 5: Prefix mounting and token enforcement

## Goal
Core derives every route's prefixes from `@Callers` (a controller declares only the resource path) and rejects a
valid token of the wrong kind on each prefix; proven end to end on Survey before any other app moves.

## Files to Create / Modify
- Create: `backend/libs/core/core-spring/src/main/java/dev/ngb/core/spring/web/CallerPrefixMapping.java` (the
  mechanism chosen by the phase 1 spike: it registers one mapping per prefix implied by the method's declared
  callers, and skips the prefix for `prefix = NONE`; `/me` for `@SelfService`; `/public/<app code>` for the public
  callers, the app code taken from `suite.app-code`)
- Create: `@SelfService` in `core-kernel` (`@Callers` of both person kinds plus the `/me` rule)
- Create: `backend/libs/core/core-suite/src/main/java/dev/ngb/core/suite/web/CallerTokenFilter.java` (prefix to
  token-kind rule; uses `SuiteJwtDecoders`' two issuers: Keycloak versus Platform; answers `403` through
  `SuiteProblems`)
- Modify: `backend/libs/core/core-openapi/.../CallerLabelRules` (add: the prefix of every path in the generated
  document agrees with the labels of its operation; holds by construction, so it guards against a bypass)
- Modify: `backend/services/apps/survey/**`: controllers drop the literal `/api/v1` from `@RequestMapping`
  (`/api/v1/surveys` becomes `/surveys`), security configuration and contract test (pilot)
- Modify: `contracts/openapi/survey.yaml` and `contracts/openapi/<caller>/survey.yaml` (regenerated)

## Rules implemented
| Declared caller (checked against the catalog in phase 2) | Prefix the mechanism adds |
|---|---|
| `TENANT_USER` | `/api/v1` |
| `TENANT_SYSTEM` | `/system/v1` |
| `PLATFORM_USER` | `/admin/v1` |
| `INTERNAL` | `/internal/v1` |
| `@SelfService` | `/api/v1/me` |
| `PUBLIC`, `PROVIDER`, `CUSTOMER_SESSION` | `/public/<app code>` (switched on in phases 9-11 as each app's public controllers drop their literal path) |

A route declared `{TENANT_USER, TENANT_SYSTEM}` is served at both `/api/v1/<path>` and `/system/v1/<path>`; a
non-grantable one only at `/api/v1/<path>`, so its `/system/v1` twin is a 404.

`/system/v1` accepts only suite tokens; `/api/v1` and `/admin/v1` accept only Keycloak JWTs; `/internal/v1` accepts
only service-client suite tokens. Exceptions: `/oauth/token`, `/.well-known/jwks.json`, actuator.

## Tasks & Steps
1. Implement the mapping mechanism and the unique-`operationId` rule for mirrored operations.
2. Implement `CallerTokenFilter` with both directions of each rule.
3. Apply to Survey; regenerate its OpenAPI; extend `CallerLabelRules` with the prefix check.
4. Tests on Survey: suite token on `/system/v1/surveys` → 200; same token on `/api/v1/surveys` → 403; Keycloak JWT
   reverse; a non-grantable operation (`survey:settings:manage`) at `/system/v1` → 404; existing Survey tests green.
5. Record in the PR description the effect on springdoc output size.

## Verification
- `./gradlew test` in `backend/libs/core` and in `backend/services/apps/survey`.
- `./gradlew :apps:app-boot:test --tests '*OpenApiContractTest'` without the update flag passes.

## Risks
- The mechanism must not need per-controller code, or phase 6 multiplies it by nine apps. If it does, stop and
  revisit the fallback from phase 1.
