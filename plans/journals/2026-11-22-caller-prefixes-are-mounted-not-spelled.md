---
title: "Caller prefixes are mounted, not spelled"
date: 2026-11-22
summary: "Phase 5 of the caller-surfaces plan: derive each route's prefix from @Callers, refuse the wrong token kind per prefix, and prove it on Survey."
---

# Caller prefixes are mounted, not spelled

## What happened

Phase 5 landed. The plan text described the goal; the work built the mechanism:

- `CallerResolver` (core-spring) became the one reading of `@Callers` / `@SelfService` / `@PublicEndpoint` / an implied caller. The OpenAPI label, the route prefix and the architecture rule now share it, so they cannot drift.
- `CallerPrefixMapping` (core-spring) registers one `RequestMappingInfo` per prefix the callers imply and unregisters the bare path, so `@RequestMapping("/surveys")` on a `{TENANT_USER, TENANT_SYSTEM}` controller is served at `/api/v1/surveys` and `/system/v1/surveys` and nowhere else. A controller still spelling the prefix (the half-moved state phase 6 removes) is left as the framework registered it.
- `CallerTokenFilter` (core-suite) refuses a valid token of the wrong kind on `/system`, `/internal`, `/api`, `/admin` with 403, reading the raw issuer/subject before the resource server resolves an actor.
- `CallerSurfaceOpenApiCustomizer` (core-openapi) narrows a route documented at two prefixes to the caller each prefix serves, and gives the twin a stable id (`list` -> `listAsTenantSystem`) instead of springdoc's order-dependent `_1`. Person-facing `/api/v1` ids are unchanged.
- `CallerLabelRules.check(document, singleCallerPerPath, callerPrefixStrict)` adds the prefix check; Survey turns both on.
- `CoreExceptionHandlers` maps `NoResourceFoundException` to 404, because a non-grantable operation has no `/system/v1` twin and the missing route surfaced as a 500.

Survey dropped every literal `/api/v1` from its `@RequestMapping` and regenerated its contract: 47 -> 85 operations, 212 KB -> 362 KB (the baseline spike predicted ~2x).

## Decision

The prefix is derived, never read back to decide a caller. A route that already spells its prefix is tolerated until phase 6 removes the literal, which is what lets a half-moved app boot while `/system/v1` twins appear early.

## Evidence

- `./gradlew build` in `backend/libs/core` and in Survey: green (arch rules included).
- `CallerSurfaceAccessTest` on Survey: suite token on `/system/v1/surveys` 200; same token on `/api/v1/surveys` 403; Keycloak JWT the reverse; `/system/v1/settings` (non-grantable) 404.
- `make docs-check` current; the matrix now shows Survey tenant-system on `/system/v1`.
- Platform's `PlatformOpenApiContractTest` fails identically on a clean stash: it needs a Keycloak at localhost:8081, an environment prerequisite, not this change.

## Next steps

- Phase 6: drop the literal prefix from the other eight apps, turn `callerPrefixStrict` on per app, and move every suite-token caller (Workflow HTTP step, seed, smoke, e2e) to `/system/v1`. `ops/seed/survey.sh` still calls `/survey/api/v1` and currently succeeds only via its admin fallback.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
