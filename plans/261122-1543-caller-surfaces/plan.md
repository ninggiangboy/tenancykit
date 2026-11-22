---
title: "Caller surfaces: explicit callers across routes, containers, contracts and docs"
description: "Every endpoint carries one of seven caller kinds in its route prefix, its OpenAPI label, its container and a generated docs matrix, with a Scalar page per caller."
status: in-progress
priority: P1
effort: 36d
branch: refactor/group-services-by-kind
tags: [refactor, backend, api, infra, docs, frontend]
blockedBy: []
blocks: []
created: 2026-11-22
---

# Caller surfaces

## Overview

Today the suite cannot answer "who may call this endpoint" from a route, a container, a contract line or a doc. The
answer is spread over path conventions (`/p/**`, `/t/**`, `/go/**`, `/portal/**`), two flags in the permission
catalogs (`scopes`, `grantable_to_tenant_system`) and an `x-allowed-callers` label that only Billing and Notification
generate. Platform mixes platform-only, tenant-only and shared operations under one `/api/v1`, and
`GET /internal/replica/{stream}` is reachable from the edge through `/platform/*`.

This plan makes the caller a first-class, mechanically derived property. It comes from the advisory session of
2026-11-22; the accepted outcome, constraints and non-goals below are the baseline for every phase. The word used for
the concept is **caller**, because "audience" already means the JWT `aud` claim and `ui_remotes.audiences`.

## Outcome, constraints, non-goals

**Outcome.** Reading a route prefix, a container name, one line of an OpenAPI file or one row of
`docs/audiences-and-surfaces.md` tells who may call an endpoint, and tests keep the four from drifting apart.

**Constraints**

- Nothing is live, so old paths are removed outright; no aliases.
- The `public` runtime is a profile of the existing jar (as `api`, `worker`, `migrate` are); no new image.
- No hand-written label or matrix without a test that fails when it drifts.
- Each phase ships green on its own; conventional commits with no plan, phase or audit labels in code, test names or
  commit messages.
- Authentication mechanisms (Keycloak, suite tokens, portal cookie) do not change.

**Non-goals.** Platform getting a public profile; new rate limiting or CORS policy (the prefixes only make one
possible); mailer (no HTTP); reorganising `docs/` into per-caller folders; keeping aliases for old paths;
`docs/system-specification.md` (historical baseline, never edited).

## The model

| Caller | Authenticates with | Route prefix | Container |
|---|---|---|---|
| `platform-user` | Keycloak JWT, platform scope | `/admin/v1/**` (Platform, Notification), `/api/v1/me/**` | `platform`, `notification-api` |
| `tenant-user` | Keycloak JWT, tenant scope | `/api/v1/**` | `<app>-api` |
| `tenant-system` | suite token (`cbk_...` credential) | `/system/v1/**` | `<app>-api` |
| `customer-session` | Billing portal cookie | `/public/billing/portal/**` | `billing-public` |
| `public` | signed link, customer token, anonymous | `/public/<app>/**` | `<app>-public` |
| `provider` | signed inbound webhook (Stripe, ad platforms, a Tenant's apps) | `/public/<app>/**` | `<app>-public` |
| `internal` | service-client suite token | `/internal/v1/**`; CI registration under `/admin/v1/ui-remotes/{name}/versions` | `platform`; never routed at the edge |

Exceptions that keep their path because the path is a standard: `POST /oauth/token` (tenant-system, internal) and
`GET /.well-known/jwks.json` (public). Platform stays one process and gets no public profile.

**Marking a route, and where its URL comes from.** A controller declares only the resource path
(`@RequestMapping("/wallets")`). A new annotation `@Callers` (on the class as a default, on a method as an exception)
states who may call it, and the **prefixes are derived from it**, one mapping per distinct prefix:

| Declared caller | Prefix added |
|---|---|
| `TENANT_USER` | `/api/v1` |
| `TENANT_SYSTEM` | `/system/v1` |
| `PLATFORM_USER` | `/admin/v1` |
| `INTERNAL` | `/internal/v1` |
| `PUBLIC`, `PROVIDER`, `CUSTOMER_SESSION` (via `@PublicEndpoint(caller)` / `@RequiresPortalPermission`) | `/public/<app code>` |

So `@Callers({TENANT_USER, TENANT_SYSTEM})` on `GET /wallets/{id}` is served at `/api/v1/wallets/{id}` and
`/system/v1/wallets/{id}`: the annotation lists the callers of the handler, and each caller gets its own route.
**Invariant: every route (path) has exactly one caller**, so `x-allowed-callers` has one value per path. The only
allow-listed exceptions, where one path is shared on purpose, are `/api/v1/me/**` (platform-user and tenant-user) and
`/oauth/token` (tenant-system and internal); a test fails on any other path with two values. The test is **switched on
per app as its prefixes land** (phase 6 for the eight apps, phase 7 for Platform and Notification), because until
then one path legitimately carries two labels: today `/api/v1/wallets/{id}` is called by both tenant-user and
tenant-system (64 of Billing's 106 labelled operations), and Platform's `users` and `roles` serve both platform and
tenant callers (13 of its 33). Two special forms: `@SelfService` (both person kinds, mounted once at `/api/v1/me`, so
`@RequestMapping("/notifications")` becomes `/api/v1/me/notifications`) and `prefix = NONE` for the standard-path
exceptions (`/oauth/token`, jwks). Every handler method must resolve to exactly one source or an architecture test
fails. For a route with a permission code a test checks the declared callers against the catalog in both directions
(`scopes` contains `tenant` iff `TENANT_USER`, contains `platform` iff `PLATFORM_USER`,
`grantable_to_tenant_system` iff `TENANT_SYSTEM`, empty scopes iff `INTERNAL`), so the code a reader sees and the
catalog Platform validates credentials and roles against cannot drift. The path is never read to decide a caller.

**One controller or two.** Same behaviour for different callers (tenant-user and tenant-system) is one controller
whose `@Callers` lists both, so it is served at both prefixes. Different behaviour (Platform `users` and `roles`: an administrator spans every Tenant, a
tenant user sees their own) is two thin controllers over the same use cases, the prefix pinning the scope and a wrong
scope answering `403`. Verified: `UserController` and `RoleController` both derive reach from `actor.scope()`
(`tenantScope()`), not from the host.

**Self-service.** Endpoints with no permission code that return only the caller's own data live under
`/api/v1/me/**` for platform and tenant users alike: `me`, `me/console-remotes`, `me/notifications`,
`me/notifications/{id}/read`, `me/notifications/read-all`, `me/notification-preferences`.

**Token type per prefix.** `/system/v1` accepts only suite tokens; `/api/v1` and `/admin/v1` accept only Keycloak
JWTs; a valid token of the wrong kind answers `403`. Billing keeps its routes at the host root (it is the proxy's
default upstream), so its prefixes are `/api/v1` and `/system/v1` without an app segment.

## Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | [Baseline and spikes](./phase-01-baseline-and-spikes.md) | Pending |
| 2 | [Caller model and label derivation](./phase-02-caller-model-and-label-derivation.md) | Pending |
| 3 | [Label contract tests and per-caller OpenAPI](./phase-03-label-contract-tests-and-per-caller-openapi.md) | Pending |
| 4 | [Scalar pages and caller matrix doc](./phase-04-scalar-pages-and-caller-matrix-doc.md) | Pending |
| 5 | [Prefix mounting and token enforcement](./phase-05-prefix-mounting-and-token-enforcement.md) | Pending |
| 6 | [Roll out prefixes to all apps](./phase-06-roll-out-prefixes-to-all-apps.md) | Pending |
| 7 | [Platform admin split and me routes](./phase-07-platform-admin-split-and-me-routes.md) | Pending |
| 8 | [Internal routes edge block](./phase-08-internal-routes-edge-block.md) | Pending |
| 9 | [Public namespace pilot: Survey and Workflow](./phase-09-public-namespace-pilot-survey-workflow.md) | Pending |
| 10 | [Public namespace: Mailroom, Attribution, Booking](./phase-10-public-namespace-mailroom-attribution-booking.md) | Pending |
| 11 | [Public namespace: Billing](./phase-11-public-namespace-billing.md) | Pending |
| 12 | [Public profile, core and architecture rules](./phase-12-public-profile-core-and-arch-rules.md) | Pending |
| 13 | [Public containers for the remaining apps](./phase-13-public-containers-remaining-apps.md) | Pending |
| 14 | [Generate contracts for Booking, Customer and Loyalty](./phase-14-generate-contracts-for-booking-customer-loyalty.md) | Pending |
| 15 | [Docs sweep and final verification](./phase-15-docs-sweep-and-final-verification.md) | Pending |

## Dependencies between phases

```text
1 -> 2 -> 3 -> 4
          3 -> 5 -> 6 -> 7 -> 8
2 -> 9 -> 10 -> 11 -> 12 -> 13 -> 15      (9 may start after 3; 12 needs 9-11; 15 needs everything)
6 -> 14 -> 15                              (14 is optional: it only removes the hand-written contracts)
```

Steps 1-4 answer "who may call this" with no URL change. Steps 5-8 make the prefix say it. Steps 9-13 move the
public surface and its process. Stop after phase 8 or phase 11 is a valid place to re-evaluate whether the `public`
containers are still wanted; the namespace and labels stay valuable without them, and merging `public` back into
`api` later means deleting a profile and its services while the routes stay.

## Key risks

| Risk | Mitigation |
|---|---|
| Spring cannot mount a second prefix cleanly, or springdoc emits duplicate `operationId`s | Spike in phase 1; fallback is generated `@RequestMapping` arrays through a build-time catalog check |
| Blocking `/internal` at the edge breaks replica rebuild | Phase 1 records how apps reach Platform in compose and ECS; phase 8 changes the address first, then blocks |
| Workflow's engine and seed/smoke/e2e scripts call apps with suite tokens on `/api/v1` | Phase 1 inventory; phase 6 moves each caller to `/system/v1` |
| A controller serves public and authenticated callers on one path | Stop condition; split before phase 9 |
| `*-public` needs the same secrets as `*-api` (link, tracking, Stripe, master keys) | Phase 12-13 list secrets per app; Terraform reuses the existing secret refs |

## Cross-plan coordination

`plans/261122-1519-suite-realtime-signals` is unrelated in scope but touches the same files: Caddy, compose,
Terraform, Notification's inbox endpoints (its shell bell) and a new SSE route. Whichever plan lands second adapts to
the first: if this plan lands first, realtime's stream route is a `tenant-user`/`platform-user` surface under
`/api/v1` and its bell uses `/api/v1/me/notifications`; if realtime lands first, phase 6-7 move its routes. This plan
does not edit that plan. Decide the order before phase 5 (recorded in phase 1's baseline report).

## Verification summary

Each Done-means item maps to a phase check in phase 15. Run per phase: the touched service's tests
(`./gradlew test` in that service), `make smoke` after any route change, `terraform validate` after any `.tf` change,
`pnpm -C frontend typecheck test` after any frontend change.

## Unresolved questions

None blocking. Assumptions carried from the advisory session: `/go/<slug>` becomes `/public/attribution/go/<slug>`;
the CI registration endpoint sits under `/admin/v1` with caller `internal`; strict token type per prefix.
