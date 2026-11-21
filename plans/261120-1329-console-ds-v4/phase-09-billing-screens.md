---
phase: 9
title: "Billing screens"
status: done
priority: P1
effort: "2d"
dependencies: [2, 4, 5, 6]
---

# Phase 9: Billing screens

## Goal
The fifteen Billing screens use the v4 kit:
- nav icons and a primary action;
- a `mobile` role on every column and no UUID columns;
- money sheets that lead with the amount;
- `danger` instead of filled destructive buttons;
- saved-view counts on invoices;
- UTC date-time fields;
- one-column forms on phones.

At the end, `mobile` becomes required, so the compiler proves that no column was missed.

## Read first
- `reference/targets/InvoicesScreen-light-1280.png`, `InvoicesPhone-light-390.png`, `InvoicePeekPhone-light-390.png`: the invoice screen is the model for every Billing list.
- `reference/design-system-v4/components/InvoicesScreen/preview.html`: the column set and the sheet's `actions`, `figure` and `eyebrow`.
- The "Rules for every screen" section of `phase-08-platform-screens.md`. They apply here unchanged; rule 3's More-menu label is `tc('actions.more')`.

## Files to create / modify
- Modify: `frontend/remotes/billing/src/routes.tsx` (icons)
- Modify: `frontend/remotes/billing/src/features/{customers,invoices,payments}/columns.tsx`
- Modify: `frontend/remotes/billing/src/features/{charges,coupons,plans,redemptions,refunds,reservations,subscriptions,wallets,webhooks}/page.tsx`
- Modify: `frontend/remotes/billing/src/features/usage/components/usage-events.tsx`
- Modify: `frontend/remotes/billing/src/features/{invoices,customers,coupons,payments,subscriptions,plans}/components/*-sheet.tsx`
- Modify: the six `datetime-local` fields and their schemas: `usage/components/record-usage-dialog.tsx`, `usage/components/aggregates-panel.tsx`, `coupons/components/create-coupon-dialog.tsx`, `coupons/schema.ts`, `pricing/components/usage-charge-panel.tsx`, `pricing/components/estimate-panel.tsx`, `pricing/schema.ts`
- Modify: `usage/components/aggregates-panel.tsx`, `pricing/components/panel-form.tsx`, `stripe/components/connection-card.tsx` (grid breakpoint and button width)
- Modify: `frontend/remotes/billing/src/i18n/{en,vi}/*.json` (only keys a step names)
- Modify: `frontend/packages/ui/src/data-table.tsx` (task 9.7 only) and `frontend/packages/ui/src/field.tsx` (task 9.6 only)
- Modify: tests that assert changed markup (only those assertions)

## Tasks

### Task 9.1: Nav icons and the shared keys
- Target files: `frontend/remotes/billing/src/routes.tsx`; `i18n/{en,vi}/common.json`.
- Steps:
  1. Screen icons:

     | Screen | Icon | Screen | Icon |
     |---|---|---|---|
     | customers | `contact` | invoices | `file` |
     | payments | `credit-card` | refunds | `undo` |
     | subscriptions | `repeat` | plans | `ticket` |
     | pricing | `sliders` | coupons | `gift` |
     | redemptions | `check-circle` | wallets | `wallet` |
     | reservations | `clock` | charges | `receipt` |
     | usage | `layout-dashboard` | webhooks | `webhook` |
     | stripe | `external` | | |

  2. Add `actions.more` to `common.json` ("More actions" / "Thao tác khác"). Also add `fields.number` ("Number" / "Số") if it is missing.
- Verify: `pnpm --filter @suite/remote-billing typecheck` exits 0.

### Task 9.2: Column roles
- Goal: every Billing column has a `mobile` role; amounts lead; no UUID columns.
- Target files: the thirteen column sets listed above.
- Steps:
  1. Amount cells render `<span className="font-semibold tabular-nums"><Money … /></span>`. Remove the second line under the amount in `invoices/columns.tsx` (the invoice number); the new `number` column carries it.
  2. Apply this table. "new" means a column to add; "delete" means remove it. When a cell renders two lines, set `cardText` to the first line.

     | Screen | primary | badge | secondary | meta | hidden / delete |
     |---|---|---|---|---|---|
     | customers | `display_name` (`cardText` name) | `status` | `email` (new, `cardOnly`) | `created_at` | delete `id` |
     | invoices | `total` | `status` | `number` (new, header `tc('fields.number')`, value `row.number`), `customer_id` (`cardText` customer name from `names`) | `created_at` | none |
     | payments | `amount` | `status` | `customer_id` | `created_at` | delete `id` |
     | charges | `amount` | `status` | `reference` | none | delete `id` |
     | coupons | `name` | `status` | `redemptions` | `created_at` | none |
     | plans | `code` | `status` | `model`, `currency` | `versions` | delete `id` |
     | redemptions | `coupon` | `status` | `discount_total`, `applications` | `redeemed_at` | none |
     | refunds | `amount` | `status` | `reason` | `created_at` | `payment_id` hidden |
     | reservations | `amount` | `status` | none | `expires_at` | `wallet_id` hidden |
     | subscriptions | `plan_code` | `status` | `customer_id` | `current_period_end` | none |
     | usage events | `metric` | none | `quantity`, `customer` | `occurred_at` | none |
     | wallets | `balance` | none | `customer_id`, `available`, `mode` | `created_at` | `currency` hidden |
     | webhooks | `url` | `status` | `event_types` | `created_at` | none |

  3. A date in `meta` uses `cardText: (row) => <value>?.slice(0, 10)`.
  4. If a deleted `id` column was the only place the ID showed, add `<CopyId value={record.id} />` to that record's sheet under `tc('fields.id')`.
- Success criteria: `grep -rn "IdValue" frontend/remotes/billing/src/features --include=*.tsx`, run from the repository root, prints only sheet files.
- Verify: `pnpm --filter @suite/remote-billing test` exits 0.

### Task 9.3: Primary actions, view counts and sheets
- Goal: every list moves its create button to `primaryAction`; invoices show view counts; money sheets lead with the amount.
- Target files: each list `page.tsx`; `invoices/page.tsx`; the six `*-sheet.tsx`, plus the inline sheets in `reservations/page.tsx`, `redemptions/page.tsx` and `webhooks/page.tsx`.
- Steps:
  1. Rule 2 from phase 8: move the create button (`icon="plus"`, or the page's main button such as "Top up" on payments) into `primaryAction`.
  2. In `invoices/page.tsx`, pass `viewCounts={useViewCounts(spec.views, (params) => invoicesOptions({ client: billing, throwOnError: true, query: asListQuery(params) }))}`, with `billing` from `useApi()`.
  3. For the sheets of invoices, payments, charges and refunds (money records):
     - `figure` is true;
     - `title` is the amount (`<Money … />`);
     - `eyebrow` is `` `${t('recordType')} · ` `` followed by `<CopyId value={record.number ?? record.id} />`;
     - `description` holds the `StatusBadge` and the key date.

     Add `recordType` to each namespace: Invoice / Hoá đơn, Payment / Thanh toán, Charge / Khoản phí, Refund / Hoàn tiền.
  4. Rule 3 from phase 8 for every sheet. The invoice sheet's actions are, in this order: Record payment (default), Mark uncollectible (outline), Void invoice (danger). Keep the accessible name "Void invoice": the e2e suite clicks it.
  5. Empty sections inside sheets ("Payments: —") become `<EmptyState>`-style one-liners with what fills them. The invoice payments line is "No payments yet. Record one when the customer pays by bank transfer." / "Chưa có thanh toán. Ghi nhận khi khách chuyển khoản.".
- Success criteria: the invoice sheet matches `reference/targets/InvoicesScreen-light-1280.png`.
- Verify: `pnpm --filter @suite/remote-billing test` exits 0, and `grep -rn 'variant="destructive"' frontend/remotes/billing/src/features --include=*-sheet.tsx` (repository root) prints nothing.

### Task 9.4: UTC date-time fields
- Goal: dates are typed and shown as `YYYY-MM-DD HH:mm` UTC (D5).
- Target files: the seven files in the "datetime" line above.
- Steps:
  1. Change each `type="datetime-local"` to `type="datetime-utc"`. Make each field's label end in " (UTC)" in both catalogues, if it does not already.
  2. In the schemas, replace `new Date(value).toISOString()` with `parseUtcInput(value)` from `@suite/ui`. For optional fields keep `undefined` for an empty value. A non-matching value fails validation: use `.refine((v) => !v || parseUtcInput(v) !== null, { message: tc('errors.utcFormat') })`, or the same pattern the schema already uses for messages, with the key `errors.utcFormat` = "Use YYYY-MM-DD HH:mm in UTC" / "Nhập theo dạng YYYY-MM-DD HH:mm (UTC)".
  3. In `aggregates-panel.tsx`, replace `localInput(...)` with `formatUtcInput(...)` and delete `localInput`.
- Success criteria: `grep -rn "datetime-local" frontend/remotes`, run from the repository root, prints nothing.
- Verify: `pnpm --filter @suite/remote-billing test` exits 0 under both `TZ=UTC` and `TZ=Asia/Saigon`.

### Task 9.5: One column on phones; Stripe buttons
- Target files: `usage/components/aggregates-panel.tsx` (line 44), `pricing/components/panel-form.tsx` (line 29), `stripe/components/connection-card.tsx` (line 27 and its buttons).
- Steps:
  1. Change `sm:grid-cols-2` to `md:grid-cols-2` in all three files.
  2. In `connection-card.tsx`, the two connection choices are cards. Their buttons must not stretch: remove `w-full`, or the class that stretches them, and align the buttons to the start of their card. Do not repeat the card title as the button label: use "Start onboarding" / "Bắt đầu kết nối" and "Enter API keys" / "Nhập API key", adding keys to `stripe.json`.
- Verify: `pnpm --filter @suite/remote-billing test` exits 0.

### Task 9.6: Remove `datetime-local` from Field
- Target files: `frontend/packages/ui/src/field.tsx`.
- Steps:
  1. Delete `'datetime-local'` from the `type` union and delete the deprecation comment.
- Verify: `pnpm typecheck` exits 0.

### Task 9.7: Make `mobile` required
- Goal: the compiler proves that every column of every list declares its phone role (M4).
- Target files: `frontend/packages/ui/src/data-table.tsx` (`DataTableColumn.mobile`).
- Steps:
  1. Change `mobile?: MobileRole` to `mobile: MobileRole`.
  2. Run `pnpm typecheck`. Every error names a column without `mobile`. In screen code, add the role from the tables in phases 8 and 9. In test files, add `mobile: 'secondary'` to the test columns. Do not make the field optional again.
- Success criteria: typecheck passes with the field required.
- Verify: `pnpm typecheck` exits 0, and `grep -n "mobile: MobileRole" frontend/packages/ui/src/data-table.tsx` (repository root) prints one line.

## Todo
- [x] Task 9.1: Nav icons and the shared keys
- [x] Task 9.2: Column roles
- [x] Task 9.3: Primary actions, view counts and sheets
- [x] Task 9.4: UTC date-time fields
- [x] Task 9.5: One column on phones; Stripe buttons
- [x] Task 9.6: Remove `datetime-local` from Field
- [x] Task 9.7: Make `mobile` required
- [x] Phase gate passed

## Phase gate
- `pnpm typecheck && pnpm test && pnpm lint`, run in `frontend/`, exits 0.

## Failure Protocol
If any Verify step does not meet its stated pass condition, STOP this phase.
Do not improvise a fix, retry blindly, or reason around the failure.
Spawn the `kongming` subagent for next-step counsel and pass:
- the phase and task id,
- what you attempted (the steps you ran),
- the exact command and its full output,
- the pass condition it failed to meet.
Apply kongming's guidance, then re-run the Verify step.
If `kongming` cannot be spawned in this environment, STOP and report the same failure evidence to the user. Never continue by self-reasoning.
