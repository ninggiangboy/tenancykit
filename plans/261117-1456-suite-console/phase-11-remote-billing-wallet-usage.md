---
title: "Phase 11: Remote billing, wallets and usage"
status: done
priority: P1
effort: "4d"
dependencies: [9]
---

# Phase 11: Remote billing, wallets and usage

## Goal

Tenant admins read wallets and the ledger, handle reservations and charges, and see usage as events and as charts.

## Screens and operations (`contracts/openapi/billing.yaml`)

| Screen | Operations |
|---|---|
| Wallets | list, get, create, ledger (`GET /wallets/{id}/ledger`) |
| Ledger entries | reverse (`POST /ledger-entries/{id}/reverse`) from the ledger view |
| Reservations | list, get, create, capture, release |
| Charges | list, get, create |
| Usage events | list, create, batch (`POST /usage-events/batch`) |
| Usage aggregates | list with chart views (Recharts through the `ui` chart wrapper) |

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

- **Irreversible, impact `Dialog`:** reverse a ledger entry (shows the entry and the resulting balance), capture a
  reservation, release a reservation, send a usage batch (item count).
- **Stat and charts:** wallet balance and usage totals use `Stat` (`text-metric`, optional `spark`); series use `chart-1`
  for the measure, `chart-2` to `chart-4` for further series, `chart-5` for the previous period.

## Files

- Create: `frontend/remotes/billing/src/features/{wallets,reservations,charges,usage}/**` and tests.

## Steps

1. Wallet list and detail: balance, held amount, status and the ledger as a paged `DataTable`; the ledger is
   append-only, so the only write is reverse, behind a `ConfirmDialog` that shows what will be reversed.
2. Reservations: capture and release show the amount and the effect on the wallet before confirming; an expired or
   already settled reservation shows the server's message.
3. Charges: create form for the operation's body; list and detail.
4. Usage events: a list, a single-event form, and a batch form that accepts pasted JSON validated against the generated
   schema before sending, reporting per-item results from the response.
5. Usage aggregates: bucketed time series and a breakdown by dimension, with the chart palette from the `ui` tokens and
   a table fallback for accessibility; the chart reads the aggregate endpoint only, never recomputes from events.
6. Tests: ledger paging and reverse flow, reservation capture and release, batch result rendering, chart renders from a
   recorded aggregate response, permission-hidden controls.

## Todo

- [x] List pattern, peek sheet, URL state, Undo toasts and impact dialogs as classified above
- [x] `en` and `vi` catalogs for every string, both languages rendered in tests
- [x] Wallets and ledger with reverse
- [x] Reservations
- [x] Charges
- [x] Usage events including batch
- [x] Usage aggregate charts with table fallback
- [x] Tests green

## Verification

```bash
cd frontend && pnpm --filter "@suite/remote-billing" test && pnpm turbo run lint typecheck build
```

and a manual pass on the compose stack with a wallet that has ledger history.

## Risks

Wallet and ledger screens deal with concurrent changes (a capture while another admin reverses); every write reads the
current `version` and reports a conflict instead of retrying silently. Large ledgers use cursor paging, not offset.

## Security

Batch JSON pasted by an admin is validated before it is sent and never evaluated. Reversals are destructive in effect
and always confirmed.
