---
title: "Phase 12: Remote billing, webhooks and Stripe"
status: done
priority: P1
effort: "3d"
dependencies: [9]
---

# Phase 12: Remote billing, webhooks and Stripe

## Goal

Tenant admins manage outbound webhook endpoints, inspect deliveries and resend one, and configure and monitor the Stripe
connection.

## Screens and operations (`contracts/openapi/billing.yaml`)

| Screen | Operations |
|---|---|
| Webhook endpoints | list, get, create, patch, rotate secret (`POST /webhook-endpoints/{id}/rotate-secret`) |
| Webhook deliveries | list, get, resend (`POST /webhook-deliveries/{id}/resend`) |
| Stripe configuration | get, delete, connect (`/stripe/configuration/connect`, `.../connect/link`), direct (`/stripe/configuration/direct`), webhook secret (`PUT /stripe/configuration/webhook-secret`), refresh |
| Stripe operations | events list, get, retry (`/stripe/events/{id}/retry`), incidents list and resolve, reconcile (`POST /stripe/reconcile`) |
| Return URLs | `GET` and `PUT /portal/return-urls` (a tenant setting managed by an admin, even though it sits under the portal prefix; confirm the operation's `x-allowed-callers` first) |

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

- **Reversible, Undo toast:** enable or disable a webhook endpoint, edit its URL, resolve a Stripe incident if the API can
  reopen it.
- **Irreversible, impact `Dialog`:** rotate a webhook secret (what stops until the receiver is updated), disconnect Stripe
  (typed confirmation, lists what stops), resend a delivery, retry a Stripe event.

## Files

- Create: `frontend/remotes/billing/src/features/{webhooks,stripe}/**` and tests.

## Steps

1. Webhook endpoint form with URL validation from the generated schema; a rotated secret is shown once in the same
   one-time dialog used for credentials in phase 8 (move that component to `ui` if it is reused).
2. Delivery list with status filters and a detail view of the attempts; resend is a confirmed action that reports the
   new delivery.
3. Stripe configuration: show connection state, the connect link flow (open the returned link, then refresh), the direct
   key form and webhook secret form with write-only fields (never echo a secret back), disconnect behind a typed
   confirmation.
4. Stripe events and incidents as lists with retry and resolve actions and the resulting state; reconcile as a button
   with its result.
5. Tests: one-time secret display, resend flow, connect link flow with a recorded response, write-only secret fields,
   permission-hidden controls.

## Todo

- [x] List pattern, peek sheet, URL state, Undo toasts and impact dialogs as classified above
- [x] `en` and `vi` catalogs for every string, both languages rendered in tests
- [x] Webhook endpoints with secret rotation
- [x] Deliveries with resend
- [x] Stripe configuration, connect, direct, webhook secret
- [x] Stripe events, incidents, reconcile
- [x] Return URLs
- [x] Tests green

## Verification

```bash
cd frontend && pnpm --filter "@suite/remote-billing" test && pnpm turbo run lint typecheck build
```

and a manual pass on the compose stack with the Stripe test configuration already used by `docs/payments-and-stripe.md`.

## Risks

Stripe keys and webhook secrets are the most sensitive values the console touches; the write-only handling and the
one-time display are tested, and the screens do not hold a secret in the Query cache.

## Security

Secrets are sent once and never returned or logged; the typed confirmation guards a destructive disconnect; the CSP
forbids loading the Stripe script from the console (Stripe.js is not used here).
