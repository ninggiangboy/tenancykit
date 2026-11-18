---
title: Remote platform and billing UIs land with the registry deploy path
date: 2026-11-18
summary: "Phases 8-12: both Module Federation remotes, permission- and entitlement-filtered navigation, the full Billing surface, and CI registration workflows."
---

# Remote platform and billing UIs land with the registry deploy path

## What happened

Implemented the five remote phases of the Suite Console plan.

- `remote-platform` now has Tenants (rename, deactivate with typed code, add domain, app entitlement switches), Users (create, enable/disable with Undo, rename, direct permissions), Roles (create, permission editor with `UnsavedBar` and Cmd/Ctrl S, assign/unassign with Undo), Tenant systems (create, rename, disable, credentials issue/rotate/revoke with a one-time secret), and the UI remote registry (enable/disable, activate a version, rollback by activating an older one).
- `remote-billing` now covers the money core (customers, payments with refunds and top-ups, refunds, invoices with void/uncollectible/record payment/create from a charge, subscriptions), pricing (plans and immutable versions, estimates, usage-charge preview, reservation quotes, coupons and codes, wallet-scoped redemptions with redeem/attach/revoke), wallets (ledger reverse, reservations capture/release, charges, usage events and batch import, aggregates with a chart and table fallback), webhooks (endpoints, secret rotation, deliveries and resend), and Stripe (connection, connect/direct/webhook secret, events retry, incidents resolve, reconcile, return URLs).
- Both remotes share the same local runtime shape: `src/lib/runtime.tsx` (clients, QueryClient, i18n, toasts, `useAction`), `src/lib/page.tsx` (URL-backed list state, `ResourcePage`, `RecordSheet`, `ConfirmDialog`, `FormDialog`, `SecretDialog`), per-feature i18n namespaces, and a router built on the shell's history and basepath.
- Added `.github/workflows/remote-platform.yml` and `remote-billing.yml`: verify, build the commit's `version+sha`, then publish and register on `dev`.

## Problems found and fixed

- **The role-create contract was lying.** `/api/v1/roles` POST reused the user `CreateRequest`. Springdoc collapsed the two nested records, so regenerating kept the wrong schema. Renamed `RoleController.CreateRequest` to `CreateRoleRequest`, regenerated the contract with `-Dopenapi.update=true`, and regenerated the frontend client. The role page now sends `{name, permissions}` against a truthful type.
- **Opening a modal dialog dismissed the non-modal record sheet**, which cleared the `open` record from the URL and made every destructive action reference an undefined record. `Sheet` now accepts `onInteractOutside`, and `RecordSheet` prevents it so the peek survives its child dialog.
- **Not every column is sortable.** The listing schemas only mark selected fields `.asSortable()`, and default sorts such as `-created_at` are not always caller-sortable. Sorting is now opt-in per listing via `ListSpec.sortable`; the server chooses its default order.
- **The usage aggregate endpoint returns totals per metric, not a time series.** The screen charts those totals and keeps the table fallback instead of inventing buckets client-side.

## Evidence

- `pnpm --filter @suite/remote-platform test`: 21 tests, including a router test that keeps `/console/r/platform/tenants` and writes `open=t1`.
- `pnpm --filter @suite/remote-billing test`: 26 tests.
- `pnpm turbo run typecheck test build`: 25 tasks successful.
- `pnpm exec biome ci .`: 0 errors.
- `./gradlew :apps:app-boot:test --tests '*PlatformOpenApiContractTest' -Dopenapi.update=true`: PASSED.

## Next steps

- The first real `ops/console/deploy-remote.sh` publish still needs compose and the `frontend-ci` secret.
- Manual pass on the compose stack for every page in both shells.
- Phase 13 (Playwright gate) and phase 14 (docs/closure) remain open.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
