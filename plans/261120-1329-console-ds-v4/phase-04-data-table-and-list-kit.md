---
phase: 4
title: "DataTable card list and list kit"
status: done
priority: P1
effort: "2d"
dependencies: [3]
---

# Phase 4: DataTable card list and list kit

## Goal
Tables follow density, mark the active row with the app accent, drop `hidden` columns on tablets and become a card list on phones. List screens get saved-view tiles with counts, a checkbox column only with real bulk actions, and a primary action that becomes a floating button on phones.

## Read first
- `reference/design-system-v4/components/DataTable/README.md` and `Tabs/README.md`.
- `reference/design-system-v4/components/bundle.js`, function `DataTable` (the `cards` branch) and `bundle.css` from `.sc-table-card` to `.sc-cardrow--selecting`.
- `reference/targets/DataTable-light-1000.png`, `InvoicesScreen-light-1280.png`, `InvoicesScreen-light-900.png`, `InvoicesPhone-light-390.png`.
- acceptance.md checks L1, L4, L5, M4, M6, M9.

## Files to create / modify
- Modify: `frontend/packages/ui/src/data-table.tsx`, `frontend/packages/ui/src/data-table.test.tsx`
- Modify: `frontend/packages/remote-kit/src/list/resource-list.tsx`, `resource-page.tsx`, `list-spec.ts`
- Create: `frontend/packages/remote-kit/src/list/use-view-counts.ts`, `use-view-counts.test.ts`
- Modify: `frontend/packages/remote-kit/src/layout/page.tsx` (`PageHeader`, `Page`)
- Modify: `frontend/packages/remote-kit/src/index.ts` (exports)

## Tasks

### Task 4.1: Column roles and layout props
- Goal: columns can say where they go on a phone, and the table can be told its layout.
- Target files: `frontend/packages/ui/src/data-table.tsx` (`DataTableColumn`, `DataTableProps`).
- Steps:
  1. Export `type MobileRole = 'primary' | 'badge' | 'secondary' | 'meta' | 'hidden'`.
  2. Add to `DataTableColumn<Row>`:
     - `mobile?: MobileRole`, with the JSDoc "Where the column goes in the phone card list; `hidden` also leaves the table below 1024px.";
     - `cardText?: (row: Row) => React.ReactNode`, with the JSDoc "Plain text for the card when `render` returns a two-line cell.";
     - `cardOnly?: boolean`, with the JSDoc "Shown only in the card list, for a value the table already shows as the second line of another cell (a code, an email).".

     Keep `mobile` optional; phase 9 makes it required. In the table branch, skip columns with `cardOnly` (no `th`, no `td`); task 4.3 adds a test for it.
  3. Add to `DataTableProps<Row>`: `layout?: 'auto' | 'table' | 'cards'` (default `auto`) and `selecting?: boolean`.
- Success criteria: the types compile.
- Verify: `pnpm --filter @suite/ui typecheck` exits 0.

### Task 4.2: Table rows follow density and the accent
- Goal: rows are 52px comfortable and 40px compact; the active row is the accent's soft fill with a 2px accent edge (L1).
- Target files: `frontend/packages/ui/src/data-table.tsx` (the row `<tr>` near line 260 and the header row near line 159).
- Steps:
  1. Replace `density === 'compact' ? 'h-9' : 'h-11'` with `density === 'compact' ? 'h-(--size-row-height-compact)' : 'h-(--size-row-height)'`. A table in compact root density then gets 40px through the variable without the prop.
  2. The active row (`data-state="active"` or the existing active marker) gets `bg-selection shadow-[inset_2px_0_0_var(--primary)]`; a selected row gets `bg-selection`.
  3. Header cells: `h-10 bg-card text-[12.5px] font-medium text-muted-foreground`.
  4. On every `th` and `td` of a column with `mobile === 'hidden'`, add the class `max-lg:hidden`.
  5. On the row-actions cell, add `[@media(hover:none)]:opacity-100`, so actions show on touch screens (M7).
- Success criteria: the table looks like `reference/targets/DataTable-light-1000.png`.
- Verify: `pnpm --filter @suite/ui test -- data-table` exits 0.

### Task 4.3: The card list
- Goal: below 768px, or with `layout="cards"`, the table renders as the card list (M4).
- Target files: `frontend/packages/ui/src/data-table.tsx`; `data-table.test.tsx`.
- Steps:
  1. Import `useMedia` and `PHONE_QUERY` from `./use-media`. Compute `const cards = layout === 'cards' || (layout === 'auto' && phone)`, with `phone = useMedia(PHONE_QUERY)` called unconditionally at the top of the component.
  2. When `cards` is true, the rows exist and the table is not loading, render instead of the `<table>`:
     - a `<ul aria-label={caption}>` inside the same outer card container;
     - for each row, an `<li tabIndex={0}>` with the classes `grid min-h-16 cursor-pointer grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-0.5 border-b border-border px-4 py-2.5 last:border-0 active:bg-accent` and `aria-selected` when `selectable`.
     - Pick the columns: `primary` (first with `mobile === 'primary'`, else the first column), `badge`, every `secondary`, and `meta`. `text(c)` is `c.cardText?.(row) ?? c.render?.(row) ?? row[c.key]`.
     - Children in order:
       - `<span class="truncate text-[15px] font-semibold">` holding primary;
       - `<span class="justify-self-end">` holding badge, or empty;
       - `<span class="truncate text-small text-muted-foreground">` holding the secondary texts joined by `' · '`, dropping empty ones;
       - `<span class="justify-self-end text-small text-muted-foreground tabular-nums">` holding meta.
     - Click or Enter calls `activate(key)` then `onRowOpen?.(row)`, except a click whose target is inside `button, a, input, label`.
     - When `selectable && (selecting || selection.length > 0)`, add a first grid column with a `Checkbox` (`grid-cols-[auto_minmax(0,1fr)_auto]`, the checkbox spanning two rows), and a tap toggles the selection instead of opening.
  3. Render the `footer` below the list as today.
  4. Tests in `data-table.test.tsx`, rendering with `layout="cards"`:
     - three rows render three `li`;
     - the first `li` contains the primary text;
     - the second line reads `INV-1 · Acme` for two secondary columns;
     - clicking an `li` calls `onRowOpen` with that row;
     - with `selectable` and `selecting`, clicking toggles `onSelect` instead;
     - with `layout="table"`, a column with `cardOnly` renders no header cell (`queryByRole('columnheader', { name })` returns null), while with `layout="cards"` its text appears on the second line.
- Success criteria: the card list matches `reference/targets/InvoicesPhone-light-390.png` row for row.
- Verify: `pnpm --filter @suite/ui test -- data-table` exits 0 with the six new tests.

### Task 4.4: Selection only with bulk actions
- Goal: no checkbox column unless the screen passes `bulkActions` (L5, and this plan's decision in `plan.md`).
- Target files: `frontend/packages/remote-kit/src/list/resource-list.tsx`.
- Steps:
  1. Change `selectable` on `DataTable` to `selectable={Boolean(bulkActions)}`.
  2. Render `BulkBar` only when `bulkActions` is given. Its children are `bulkActions(selected)`. Remove the `BulkCopyIds` fallback, but keep `bulk-copy-ids.tsx` and its export so a screen can opt in.
- Success criteria: a `ResourceList` without `bulkActions` renders no checkbox.
- Verify: `pnpm --filter @suite/remote-kit test` exits 0. If a test expected checkboxes on a list without `bulkActions`, change it to pass `bulkActions={(keys) => <BulkCopyIds ids={keys} />}`, and keep its other assertions.

### Task 4.5: Saved-view counts and tiles
- Goal: lists with saved views show tiles with each view's total (L4).
- Target files: create `frontend/packages/remote-kit/src/list/use-view-counts.ts` and its test; modify `resource-list.tsx` and `resource-page.tsx`; export from `index.ts`.
- Steps:
  1. `useViewCounts(views: ViewSpec[] | undefined, optionsFor: (params: Record<string, string>) => UseQueryOptions<{ page?: PageInfo | null }>): Record<string, number | undefined>`.
     - For each view, build `params = listQuery({ filters: view.filters ?? [], limit: 1, pageInfo: 'full' })` (import `listQuery` from `@suite/api-client`).
     - Run all of them with `useQueries({ queries: views.map((view) => optionsFor(params)) })`.
     - Return `{ [view.id]: result.data?.page?.total ?? undefined }`.
  2. Test with a fake `optionsFor` whose `queryFn` returns `{ page: { total: 7 } }` for every call, and assert that the hook returns `{ all: 7, open: 7 }` for two views. Wrap it in a `QueryClientProvider`.
  3. `ResourceList` gets `viewCounts?: Record<string, number | undefined>`. The `Tabs` it renders gets `variant="tiles"` and `count: viewCounts?.[view.id]` per item. `ResourcePage` passes `viewCounts` through.
- Success criteria: a screen can show counts by passing `viewCounts={useViewCounts(spec.views, (params) => invoicesOptions({ client, query: asListQuery(params) }))}`.
- Verify: `pnpm --filter @suite/remote-kit test -- use-view-counts` exits 0.

### Task 4.6: The page's primary action
- Goal: a page names its one primary action. It sits in the header from 768px up and is a floating button on phones (M6).
- Target files: `frontend/packages/remote-kit/src/layout/page.tsx` (`PageHeaderProps`, `PageHeader`, `Page`); `resource-page.tsx`.
- Steps:
  1. Add `primaryAction?: ReactNode` to `PageHeaderProps`. Its JSDoc: "The one main action: a `Button` with the default variant. A floating button on phones."
  2. In `PageHeader`, render `actions` then `primaryAction` inside the actions group. Wrap `primaryAction` in `<span className="max-md:hidden">`.
  3. In `Page`, after the body, render the same `primaryAction` inside `<div className="fixed right-4 bottom-[calc(1rem+env(safe-area-inset-bottom))] z-20 md:hidden [&_button]:h-13 [&_button]:rounded-2xl [&_button]:px-5 [&_button]:text-[15px] [&_button]:shadow-lg">`.
  4. Page padding: the header and body use `px-(--page-pad)` instead of `px-6`; the body gap is `gap-(--page-gap)`.
  5. Title: `PageHeader` already uses `text-title` (24/30/700 since phase 1); add `max-md:text-[22px] max-md:leading-7`.
  6. `ResourcePage` passes `primaryAction` through.
- Success criteria: the existing screens still render. They do not use `primaryAction` yet; phases 8 and 9 move their create buttons into it.
- Verify: `pnpm --filter @suite/remote-kit test` and `pnpm typecheck` exit 0.

## Todo
- [x] Task 4.1: Column roles and layout props
- [x] Task 4.2: Table rows follow density and the accent
- [x] Task 4.3: The card list
- [x] Task 4.4: Selection only with bulk actions
- [x] Task 4.5: Saved-view counts and tiles
- [x] Task 4.6: The page's primary action
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
