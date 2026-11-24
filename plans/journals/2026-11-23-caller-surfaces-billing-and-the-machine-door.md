---
title: "Caller surfaces: Billing on two doors, and what Notification waits for"
date: 2026-11-23
summary: "Phase 6 closed: Billing mounted under /api/v1 and /system/v1, 405 instead of 500, Notification limited to the token filter, stack checks not run"
---

# Caller surfaces: Billing on two doors, and what Notification waits for

## What shipped

- Billing's controllers declare only their resource path; the prefix mapping, the token filter and the surface
  customizer are wired as in the other apps, and the contract test runs with the one-caller and prefix rules on.
  64 machine operations are now served at `/system/v1`, 85 person operations at `/api/v1`.
- Billing's HTTP tests call each operation at the door its token belongs to. Where a test used to assert that a tenant
  system is refused an administrator-only operation (Stripe configuration, ledger reversal, voiding an invoice, setting
  prices) it now asserts the new answer: `404` at `/system/v1` where the operation has no machine door, `405` where the
  path is shared with a read-only machine route, `403 wrong-token-kind` at `/api/v1`.
- A request whose method a path does not serve answers `405` with an `Allow` header in `CoreExceptionHandlers`. It used to
  fall into the catch-all and answer `500`; two doors on one path made that easy to reach.
- The Billing client in `frontend/packages/api-client` was regenerated from the merged contract; the caller matrix and
  the docs that showed a machine calling `/api/v1` were updated.

## Decision

Notification gets only the token filter in this phase. Its templates controller spells `/api/v1` while its caller implies
`/admin/v1`, and the mapping treats a controller that spells a different literal prefix as unmoved, so wiring the mapping
now doubled the path (`/admin/v1/api/v1/templates`). Wiring waits for phase 7, which moves templates, inbox and
preferences in one change and turns the one-caller rule on there.

## Evidence

- `./gradlew test` passes in `backend/services/apps/billing`, `backend/services/infra/notification` and `backend/libs/core`.
- `pnpm typecheck` and `pnpm test` pass in `frontend`.
- `ruby ops/docs/caller-matrix.rb --check` passes after `--write`.
- One Billing test, `InvoiceBrokerPublishIntegrationTest`, failed once in a full run and passed alone twice and in the
  next full run; it reads a shared exchange, so treat it as order-dependent until proven otherwise.

## Not verified

`make up-all`, `make smoke` and `pnpm -C frontend e2e` were not run: no suite images were built on the machine. Run them
before merging phase 6.

## Next

Phase 7: Platform and Notification split by prefix, `/me` routes, and the snapshot endpoint under `/internal/v1`.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
