---
title: "Phase 9: Remote billing, money core"
status: done
priority: P1
effort: "6d"
dependencies: [7]
---

# Phase 9: Remote billing, money core

## Goal

Create `remote-billing` with its pipeline and ship the core money flow: customers, payments and refunds, invoices,
subscriptions. Phases 10 to 12 add the other groups to the same remote in their own feature folders.

## Screens and operations (`contracts/openapi/billing.yaml`)

| Screen | Operations |
|---|---|
| Customers | list, create, get, update, deactivate, lookup by external user id |
| Payments | list, get, create refund (`POST /payments/{id}/refunds`), create top-up (`POST /payments/top-ups`) |
| Refunds | list, get |
| Invoices | list, get, create, void, mark uncollectible, record a payment (`POST /invoices/{id}/payments`) |
| Subscriptions | list, get, create, cancel |

Out of this remote: everything under `/portal/api` (the customer portal) and `/stripe/webhooks`.

## Language

Every user-visible string goes through the remote's own `i18next` instance (initialised from `ctx.locale`, reacting to
changes) with catalogs `src/i18n/en/*.json` and `src/i18n/vi/*.json`, one namespace per feature. Tests set a
`missingKeyHandler` that throws, and render each page of this phase in `en` and `vi`; a key present in one catalog and
absent in the other fails the test. Text from API responses is shown as returned.

## Design

Follow the design system rules in [plan.md](./plan.md). List pages use the pattern of the `InvoicesScreen` sample: title and
description on the left with Export and the one primary action on the right, segmented `Tabs` for saved views, a one-line
toolbar (search, `FacetedFilter`, `FilterChip`s, View), `DataTable` with rows that lead with what people scan for and an
id on the second line, the floating `BulkBar` while rows are selected, and a record `Sheet` that has its own URL. Each
phase lists below which of its actions are reversible (Undo toast) and which are not (impact `Dialog`).

- **Reversible, Undo toast:** none of the money actions; customers can be updated in place.
- **Irreversible, impact `Dialog`:** refund (amount, remaining refundable), void invoice, mark uncollectible, cancel
  subscription, deactivate customer, each naming the object and what is kept (the wallet is untouched by a void).
- **Sample to match:** the invoice list is the `InvoicesScreen` sample (amount and status lead, then who it is for, id on
  the second line, `CopyId`, `Money`, `StatusBadge`, UTC column header). Map Billing's status enums to tones in one place.

## Files

- Create: `frontend/remotes/billing/src/{routes,features/customers,features/payments,features/invoices,features/subscriptions}/**`,
  `.github/workflows/remote-billing.yml` (same shape as the platform one).
- Modify: `frontend/remotes/billing/package.json`.

## Steps

1. Scaffold the remote with `defineRemote`, the Billing navigation, and the pipeline; deploy it through the registry
   and confirm the Tenant Shell shows Billing only for a tenant with the `billing` entitlement.
2. Read each operation, its `x-required-permission` and `x-allowed-callers` (the console acts as a `tenant-user`); use the
   generated hooks and `listQuery`.
3. Lists on `DataTable` with the filters each endpoint declares; `page_info=full` for the visible total, cursor paging
   where the list can exceed the offset cap.
4. State-changing actions (refund, void, cancel, deactivate, mark uncollectible) go through `ConfirmDialog` and send an
   `Idempotency-Key` where the operation declares one and `If-Match` where it declares one.
5. Money and instants through the shared formatters; read Billing's money representation first, do not assume decimals.
6. Tests: one MSW-backed flow per screen plus a permission-hidden-control test per mutating action.

## Todo

- [x] List pattern, peek sheet, URL state, Undo toasts and impact dialogs as classified above
- [x] `en` and `vi` catalogs for every string, both languages rendered in tests
- [x] Remote scaffold, pipeline, first deploy through the registry
- [x] Customers
- [x] Payments, refunds, top-up
- [x] Invoices with all actions
- [x] Subscriptions
- [x] Tests green

## Verification

```bash
cd frontend && pnpm --filter "@suite/remote-billing" test && pnpm turbo run lint typecheck build
```

and a manual pass on the compose stack with a tenant that has Billing enabled.

## Risks

Money rounding and currency display errors are the costly bug here; one formatter is used everywhere and is tested
against Billing's own amounts. A refund or void clicked twice must not be sent twice: the idempotency key is generated
once per dialog open.

## Security

Mutations rely on Billing's permission checks; the UI hides controls but is not the control. No amounts or customer
data are logged to the console.
