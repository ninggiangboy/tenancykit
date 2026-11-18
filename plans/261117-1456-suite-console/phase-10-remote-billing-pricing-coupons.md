---
title: "Phase 10: Remote billing, pricing and coupons"
status: done
priority: P1
effort: "4d"
dependencies: [9]
---

# Phase 10: Remote billing, pricing and coupons

## Goal

Tenant admins author and retire pricing plans, try prices, and manage coupons, promotion codes and redemptions.

## Screens and operations (`contracts/openapi/billing.yaml`)

| Screen | Operations |
|---|---|
| Pricing plans | list, get, create, add a version (`POST /pricing-plans/{id}/versions`), retire |
| Price check | estimate (`POST /pricing/estimates`), usage charge preview and read (`POST /pricing/usage-charges`, `GET /pricing/usage-charges/{id}`), reservation price (`POST /pricing/reservations`) |
| Coupons | list, get, create, deactivate, codes list and create (`/coupons/{id}/codes`) |
| Promotion codes | deactivate (`POST /promotion-codes/{id}/deactivate`) |
| Coupon redemptions | get, revoke (`/coupon-redemptions/{id}/revoke`), per wallet list, redeem and attach (`/wallets/{walletId}/coupon-redemptions...`) |

Wallet-owned pages that need a wallet picker reuse the wallet list built in phase 11; build the picker component here
against the list endpoint so the two phases do not wait on each other.

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

- **Reversible, Undo toast:** deactivate a coupon or promotion code when the API can reactivate it; otherwise treat it as
  irreversible (check the operations first).
- **Irreversible, impact `Dialog`:** retire a pricing plan (counts of subscriptions on it), revoke a redemption.

## Files

- Create: `frontend/remotes/billing/src/features/{pricing-plans,pricing-check,coupons,redemptions}/**` and tests.
- Modify: `frontend/remotes/billing/src/routes` (navigation entries only).

## Steps

1. Read the pricing plan and version schemas in the OpenAPI; a plan version is immutable, so the edit flow creates a new
   version from the previous one rather than editing in place.
2. Plan form with Zod schemas derived from the generated types; show server validation errors next to the field.
3. Estimate and charge preview screens render exactly what the API returns, including the breakdown, with no client-side
   price math.
4. Coupon, code and redemption screens with the actions above behind `ConfirmDialog`.
5. Tests: plan version creation flow, estimate rendering from a recorded response, revoke and deactivate flows,
   permission-hidden controls.

## Todo

- [x] List pattern, peek sheet, URL state, Undo toasts and impact dialogs as classified above
- [x] `en` and `vi` catalogs for every string, both languages rendered in tests
- [x] Pricing plans with versions and retire
- [x] Price check screens
- [x] Coupons and codes
- [x] Promotion code deactivate
- [x] Redemptions with wallet picker
- [x] Tests green

## Verification

```bash
cd frontend && pnpm --filter "@suite/remote-billing" test && pnpm turbo run lint typecheck build
```

and a manual pass on the compose stack.

## Risks

Doing price arithmetic in the browser would drift from the server; the plan forbids it and the tests use recorded
server responses. A retired plan with live subscriptions must show a clear server error, not a generic one.

## Security

Coupon codes are secrets of a kind: show a created code in the response view, never in a URL or the query cache after
the dialog closes.
