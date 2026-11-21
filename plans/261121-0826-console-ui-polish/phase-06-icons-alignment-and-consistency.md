---
phase: 6
title: "Icons, alignment and consistency"
status: completed
priority: P2
effort: "2d"
dependencies: [2, 4]
---

# Phase 6: Icons, alignment and consistency

## Goal

Icons say what the control does, rows line up, and one control looks the same wherever it appears. Fixes E13 and E14 and
the English strings the review found hard-coded. It runs after phases 2 and 4 because it edits the same sheet files.

## Files to Create / Modify

- Modify: `frontend/packages/ui/src/icon.tsx` (new glyphs)
- Modify: `frontend/packages/ui/src/{dropdown-menu,checkbox,field,copy-id,filter-chip,tabs,data-table,messages}.tsx`
  (`messages.test.tsx` must still pass)
- Modify: `frontend/packages/shell-core/src/frame/header.tsx` and the shell catalogue (`shell.breadcrumb`)
- Modify: `frontend/packages/remote-kit/src/record/detail-rows.tsx`
- Create: `frontend/packages/remote-kit/src/record/row-list.tsx` (+ test)
- Modify: `frontend/packages/remote-kit/src/dialogs/{form-dialog,confirm-dialog}.tsx` (footer button size)
- Modify: `frontend/remotes/platform/src/routes.tsx` and `frontend/remotes/billing/src/routes.tsx` (nav icons, plus a test)
- Modify, the Edit and Rename buttons: `remotes/platform/src/features/{roles,users,systems,tenants}/components/*-sheet.tsx`,
  `remotes/billing/src/features/customers/components/customer-sheet.tsx`
- Modify, the hand-rolled row lists (find them with `grep -rn --include=*.tsx "divide-y divide-border rounded-lg border"
  frontend/remotes`): at least `platform/.../remotes/components/remote-sheet.tsx`, `billing/.../wallets/components/ledger.tsx`,
  `billing/.../webhooks/components/deliveries.tsx`, `billing/.../invoices/components/invoice-sheet.tsx` (items and
  payments), `platform/.../tenants/components/tenant-apps.tsx`
- Modify: `frontend/e2e/tests/13-interaction-audit.spec.ts`

## Tasks & Steps

### 6.1 The icon vocabulary
1. Add glyphs to `icon.tsx` (type, import and map together): `pencil`, `sun`, `moon`, `monitor`, `minus`, `layers`,
   `plug`. They are Lucide icons already in the dependency.
2. Rename uses of `settings` that mean "edit" to `pencil`: Edit and Rename buttons in the five sheets above and the
   tenant sheet's Rename menu item. `settings` stays for a settings screen.
3. The half-checked checkbox shows `minus`, not `x` (`checkbox.tsx`).
4. The shell's theme control: the trigger shows the icon of the choice made, `sun`, `moon` or `monitor` (System shows
   `monitor`, not the icon of the look it resolves to), and the three items use the same three icons. Remove `sparkle` from `IconName` and the map once nothing uses it.
5. `DropdownMenu` items get `checked?: boolean`: a trailing `check` icon, `role="menuitemradio"` and `aria-checked`.
   Use it for theme, language (the phone's account menu too) and density; density's current trick of swapping the leading
   icon for a check goes away.
6. "Activate a version" (`remote-sheet.tsx`) uses `check-circle`; `refresh` stays for Retry, Resend and Rotate.
7. Nav icons (`routes.tsx`), changed items only: Pricing plans `layers` (was `ticket`), Coupons `ticket` (was `gift`),
   Stripe `plug` (was `external`). Rule: within one app no two nav items share an icon. Add a test per remote that reads
   its `sections` and asserts uniqueness, and that every icon name exists in `IconName`.

### 6.2 Rows that line up
1. `DetailRow`: the label and the value share a baseline (`items-baseline`) and the row has a minimum height, so a badge
   (22px), a mono id and plain text sit on one line. A value that wraps still starts under the label's first line.
2. Create `RowList` and `ListRow` in `remote-kit`: a `ul` with the border and dividers the screens hand-roll today, and
   rows with named slots, `lead`, `title`, `meta`, `trailing`, a minimum height of 56px and `items-center`. The trailing
   slot has a stable width (`min-w-24 justify-end`) so a button and a badge occupy the same place.
3. Migrate the lists above:
   - Versions: the version in mono on the first line (truncated with the full text in `title`), "SDK major · time" on
     the second, and the trailing slot holds either the Activate button or the Active badge. Every row is then the same
     height, and the active one has the selection background.
   - Ledger: the amount and the Reverse button are separate, the reference wraps under the entry instead of being cut
     at 20 characters, and the button sits in the trailing slot.
   - Deliveries, invoice items and payments, tenant apps: the same slots.
4. Test `RowList`: a row with a button and a row with a badge have the same height.

### 6.3 One look for one control
1. Native `<select>` stays (phones get the native picker; every form binding keeps working). In `field.tsx` give it
   `appearance-none`, the same border, height, font and `pr-8` as the combobox trigger, and the `chevrons-up-down` icon
   in a `pointer-events-none` wrapper at the right.
2. `FormDialog` and `ConfirmDialog` footer buttons move from `size="sm"` to the default size so they match the inputs
   above them; phones already make them 44px.
3. `CopyId`: the copy icon keeps a fixed 14px slot and only its opacity changes on hover and focus. Animating its width
   moves the text beside it on every hover.
4. Check the phone: no change to touch targets (the audit's tap-target check must still pass).

### 6.4 Strings that were left in English
1. `DataTable` header `aria-label="Actions"` becomes `t('table.actions')`; the shell breadcrumb `aria-label` becomes
   `t('shell.breadcrumb')`; `FilterChip`'s "Remove the … filter" and its operator words (`is`, `is not`, `matches`…)
   become `filter.remove` and `filter.op.*` keys. Add each key to the English and Vietnamese catalogues; `messages.test.tsx`
   and the shell's key test fail on a missing one.
2. Tabs count: `toLocaleString('en-US')` becomes the active locale. Export a `useUiLocale()` from `messages.tsx` for it.
3. Leave the example placeholders (`invoice.created`, `billing:invoice:read`, `sk_live_…`): they are literal formats.

## Verification

- `pnpm --filter @suite/ui test`, `@suite/remote-kit`, `@suite/shell-core`, and both remotes' tests pass.
- `cd frontend && pnpm turbo run lint typecheck test build` passes.
- Redeploy; in `13-interaction-audit.spec.ts`:
  - the theme trigger's icon changes with the choice, the three items have three different icons, and the current one has
    `aria-checked="true"`;
  - on the remote versions sheet every row is the same height and the button or badge centre is within 1px of the
    row's centre;
  - on any sheet's detail rows the label and value baselines differ by at most 1px for single-line rows;
  - a `select` in a dialog and an `input` beside it have the same height;
  - hovering a `CopyId` changes the width of the text beside it by 0px;
  - with the language set to Vietnamese, none of "Actions", "Breadcrumb" or "Remove the" appears in an accessible
    name.
- `12-screen-audit.spec.ts` and `10-visual.spec.ts` still pass; look at the new captures of the versions sheet, the
  wallet sheet and the webhook sheet (light, dark, 390 wide) and attach them to the phase report.

## Risks / rollback

- `RowList` is a new shared component: keep its slots minimal; a screen that needs more can keep its own markup.
- Icons are one file; reverting a mapping is one line.
