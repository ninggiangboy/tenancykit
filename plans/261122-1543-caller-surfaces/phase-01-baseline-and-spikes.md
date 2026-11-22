---
phase: 1
title: "Baseline and spikes"
status: done
priority: P1
effort: 1d
dependencies: []
---

# Phase 1: Baseline and spikes

## Goal
Settle every item the design still treats as belief, and record the answers in `reports/baseline.md`, before any
production code changes.

## Files to Create / Modify
- Create: `plans/261122-1543-caller-surfaces/reports/baseline.md`
- Create (throwaway, not merged): a spike test under `backend/services/apps/survey/apps/app-boot/src/test/`

## Tasks & Steps
1. **Catalog loading.** For each of the 10 backends, find how `contracts/permissions/<app>.yaml` is read (Billing has
   `BillingPermissionCatalog`; Platform has `CatalogTenantSystemScopes`). Decide where one shared reader lives
   (`core-suite` or `core-openapi`). Record the class per app.
2. **Second prefix spike (Survey).** In a test, register a `/system/v1/...` mapping for each method whose
   `@RequiresPermission` code is grantable (a `RequestMappingHandlerMapping` customisation). Confirm that both paths
   route to the same method, that springdoc lists both, and that `operationId`s can be made unique. Record the
   working approach or the fallback.
3. **Self-service data.** Read Notification's inbox, preferences, read and read-all controllers and queries and
   confirm each returns only the caller's rows. Read Platform `me` and `console/remotes` the same way. Record that
   `RoleController` matches `UserController` (reach from `actor.scope()`); already seen, keep the evidence.
4. **How apps reach Platform.** In `docker-compose.yml` find how `platform.localhost:8080` resolves inside the
   containers (Caddy alias or direct); in `ops/terraform` confirm `PLATFORM_INTERNAL_HOSTNAMES` and the internal FQDN.
   Record whether blocking `*/internal/*` at the edge can break the replica rebuild or the replica listener.
5. **Remaining unknowns.** Kubernetes manifests (`grep -rl "kind: Deployment" .`); whether Billing serves any HTML
   under `/portal/`; Booking's root `/p/` alias; Workflow's dual `/hooks` spelling; where `x-public` is emitted; which
   `@Authenticated` operations exist in Billing and whether any is meant for tenant-system.
6. **Mixed-path check (stop condition).** For every public controller confirm that no authenticated operation shares
   its class or path prefix.
7. **Tooling for the matrix.** Confirm which YAML-capable runtime CI can use for the generator in phase 4 (Node with
   the `yaml` package from the pnpm workspace; fallback Ruby). The Python on the dev machine has no `yaml` module.
8. **Old-path inventory.** Search for `/survey/p/`, `/go/`, `/t/`, `/mailroom/t/`, `/booking/p/`, `/portal/`,
   `/stripe/webhooks`, `/workflow/hooks`, `/internal/replica`, `/api/v1/console`, `/api/v1/notifications` across
   `backend`, `contracts`, `ops`, `frontend` (excluding `node_modules`, `dist`, compiled output), `docs`, `.github`,
   `Makefile`, `docker-compose.yml`. List the files per later phase.
9. **Suite-token callers.** List every caller of `/api/v1` with a suite token: Workflow's HTTP step, `ops/seed/*.sh`,
   `ops/smoke/*.sh`, `ops/loadtest/*.sh`, `frontend/e2e/support/*.ts`, docs examples.
10. **Realtime plan order.** Decide with the user which of this plan and `261122-1519-suite-realtime-signals` lands
    first; record the rule from `plan.md`.

## Verification
- `reports/baseline.md` answers each item above with file evidence and marks every stop condition hit or clear.
- The spike test passes locally and is discarded, or kept as the test of the final mechanism in phase 5.

## Risks
- The spike fails: choose the fallback in `plan.md` before phase 5 and update phase 5.
- A stop condition is hit (mixed public and authenticated path, a no-permission endpoint returning per-caller data,
  an `/internal` block breaking rebuild): stop and ask.
