---
phase: 6
title: "Roll out prefixes to all apps"
status: pending
priority: P1
effort: 3d
dependencies: [5]
---

# Phase 6: Roll out prefixes to all apps

## Goal
Billing, Mailroom, Attribution, Booking, Customer, Loyalty, Workflow and Notification expose `/api/v1` and
`/system/v1` per the catalog, and every suite-token caller moves to `/system/v1`.

## Files to Create / Modify
- Modify (each app, one commit per app): `backend/services/apps/{billing,mailroom,attribution,booking,customer,loyalty,workflow}/apps/app-boot/**`
  and `backend/services/infra/notification/apps/app-boot/**` (security configuration, wiring of the phase 5
  mechanism, contract tests)
- Modify (hand-written YAML): `contracts/openapi/booking.yaml`, `customer.yaml`, `loyalty.yaml` gain the
  `/system/v1` paths and their labels
- Modify (generated): `contracts/openapi/<app>.yaml` and `contracts/openapi/<caller>/<app>.yaml`
- Modify (suite-token callers, from the phase 1 inventory): Workflow's HTTP step and connection tests under
  `backend/services/apps/workflow/`, `ops/seed/*.sh`, `ops/smoke/*.sh`, `ops/loadtest/*.sh`,
  `frontend/e2e/support/*.ts`
- Modify: `contracts/tokens/README.md`, `docs/webhooks-and-events.md` and any doc whose example calls an app with a
  suite token on `/api/v1`

## Tasks & Steps
1. Per app: wire the mechanism, drop the literal `/api/v1` from every controller's `@RequestMapping` so `@Callers`
   alone decides the prefixes (a codemod, reviewed by hand), regenerate or hand-edit the contract, add the two-direction token tests, run the app's
   tests. Billing keeps its routes at the host root, so its prefixes carry no app segment.
2. Notification: no `/system/v1` (no grantable code); `/admin/v1` for templates is phase 7.
3. Move every suite-token caller to `/system/v1`; keep the tenant-user callers (console remotes) on `/api/v1`.
4. Bring the stack up (`make up`) and run `make smoke`; any 403 on `/api/v1` from a script means a caller was missed.
5. When an app's paths are split, turn on `singleCallerPerPath` in its contract test (the rule from phase 3); the app's
   generated document must then have exactly one caller per path. Do this per app in the same commit that splits it,
   for the eight apps handled here.

## Verification
- `./gradlew test` in each touched service.
- `make smoke` and `pnpm -C frontend e2e` pass against the local stack.
- A search for suite-token calls on `/api/v1` in `ops`, `frontend`, `docs` and `backend` returns nothing.

## Risks
- Hand-written YAML apps get ~2x paths by hand; the phase 3 comparison test must stay strict.
