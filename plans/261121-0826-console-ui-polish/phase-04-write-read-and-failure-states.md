---
phase: 4
title: "Write, read and failure states"
status: completed
priority: P1
effort: "2.5d"
dependencies: [1, 2]
---

# Phase 4: Write, read and failure states

## Goal

Every action and every read shows what state it is in. A write shows progress, a read inside a sheet shows loading, a
failure shows the server's sentence where the person is looking, and a failed read never looks like an empty one. Fixes
E6, E7, E8 and E9.

## Files to Create / Modify

- Create: `frontend/packages/remote-kit/src/layout/query-state.tsx` (+ test)
- Modify: `frontend/packages/remote-kit/src/layout/error-panel.tsx`
- Modify: `frontend/packages/remote-kit/src/runtime/use-action.ts`
- Modify: `frontend/packages/remote-kit/src/dialogs/{form-dialog,confirm-dialog}.tsx` (+ tests)
- Modify: `frontend/packages/remote-kit/src/list/{resource-list,resource-page}.tsx`
- Modify: `frontend/packages/remote-kit/src/i18n/kit-catalog.ts` and the Vietnamese catalogue beside it
- Modify: `frontend/packages/ui/src/{switch,button,data-table,toaster}.tsx` and `styles/theme.css` (a `progress` keyframe)
- Modify, the six sections that read inside a sheet:
  `remotes/billing/src/features/invoices/components/invoice-sheet.tsx`,
  `payments/components/payment-sheet.tsx`, `subscriptions/components/subscription-sheet.tsx`,
  `coupons/components/coupon-codes.tsx`, `wallets/components/ledger.tsx`, `webhooks/components/deliveries.tsx`
- Modify, the two switches: `remotes/platform/src/features/tenants/components/tenant-apps.tsx` and
  `hooks/use-tenants.ts`, `remotes/platform/src/features/remotes/columns.tsx` and `hooks/use-remotes.ts`
- Modify, the buttons that write without a dialog (all under `remotes/platform/src/features/`):
  `remotes/components/remote-sheet.tsx` (Activate), `users/components/user-sheet.tsx` (enable or disable),
  `users/components/direct-permissions.tsx`, `roles/components/assign-user.tsx`, `roles/components/role-permissions.tsx`
- Modify, 38 dialog call sites (21 `FormDialog`, 17 `ConfirmDialog`): find them with
  `grep -rln "<FormDialog\|<ConfirmDialog" frontend/remotes`
- Modify, the 17 list screens that use `ResourcePage` or `ResourceList` (to pass `refreshing`)
- Modify: `frontend/e2e/tests/13-interaction-audit.spec.ts`

## Tasks & Steps

### 4.1 A read has three outcomes
1. Create `QueryState` in `remote-kit`: props `query` (the parts of a TanStack query it reads: `isLoading`, `isError`,
   `error`, `data`, `refetch`), `isEmpty?: (data) => boolean`, `skeletonHeight?: number`, `empty: ReactNode`, and
   `children: (data) => ReactNode`. It renders the skeleton while `isLoading`; on `isError` an `Alert tone="danger"`
   with the server's sentence (`errorMessage(error)`) and a Retry button that calls `refetch`; then `empty` when
   `isEmpty(data)` (default: an empty array); else `children(data)`. A background refetch that fails keeps showing the
   last data and shows the same alert above it.
2. Replace the ad-hoc `isLoading ? <Skeleton/> : items.length === 0 ? ... : ...` in the six sections listed. Search for
   any other section with the same shape: `grep -rn "isLoading" frontend/remotes --include=*.tsx` and convert each that
   lives inside a sheet or a tab (list screens use `ResourceList`, handled in 4.2).
3. i18n: add `state.sectionFailed` (title) to both kit catalogues; the sentence under it is the server's. `keys.test.ts`
   must pass.
4. Test: loading, failed (shows the sentence and Retry; clicking Retry refetches), empty, and a failed refetch over
   existing data.

### 4.2 A failed list says why
1. `ErrorPanel`: keep the title and the hint, and show `errorMessage(error)` as the description when the error carries a
   server sentence (an `ApiError` or a problem object); otherwise keep the hint alone. A 403 keeps the forbidden look.
2. Test: an `ApiError('Database is down')` shows that sentence; an error with no sentence shows the hint.

### 4.3 A list that refreshes says so
1. `DataTable` gets `refreshing?: boolean`. While it is true for more than 150ms (reuse `useDelayed`) the frame shows a
   2px bar along its top edge, built from a child translated with a `progress` keyframe added to `theme.css`
   (`--animate-progress`), and the table has `aria-busy="true"`. Reduced motion shows the bar static.
2. `ResourceList` takes `refreshing` and passes it on; each of the 17 screens passes `refreshing={query.isFetching &&
   !query.isLoading}`. `loading` stays `query.isLoading`.
3. The rows stay as they are while refreshing (`keepPreviousData`); do not dim them.

### 4.4 A write in progress
1. `Switch`: `busy` must not look disabled. Keep the Radix `disabled` (it stops a second toggle) but add `data-busy` and
   neutralise the dim: `data-[busy]:disabled:opacity-100 data-[busy]:cursor-progress`. Put a 12px `loader` icon with
   `animate-spin` inside the thumb while busy. The switch's width and position never change.
2. `Button`: with `loading` and no `icon`, the spinner must not widen the button. Render the label with `invisible` and
   overlay the spinner centred, so the width is the label's. A button with an icon keeps swapping the icon.
3. Buttons that start a write directly, with no dialog, are the ones listed under "Files" above: Activate (it only
   sets `disabled` today), the user enable or disable button, the direct-permission and role-assignment buttons, and the
   role permission save. Confirm the list with `grep -rn --include='*.tsx' "\.mutate(" frontend/remotes` and keep the
   hits that sit in an `onClick` or a handler, not in a dialog's `onSubmit` or `onConfirm`. Each such button gets
   `loading` while its own request is pending, not while any request is.

### 4.4b Narrow what a toggle refetches
1. `useAction` keeps refetching every mounted query by default. The two switch hooks (`useSetTenantApp` and
   `useRemoteState`) pass `invalidate` with the queries of their own family. The generated
   query keys carry the operation name, so a partial key matches: `[{ _id: 'listTenants' }]`, or the generated
   `<operation>QueryKey(...)` helper where one exists in `@suite/api-client/*/query`.
2. A test in `use-action` asserts that a write with `invalidate` leaves another mounted query alone.

### 4.5 A failed write is shown where the person is
1. `useAction`: an error toast lasts 10 seconds (pass `duration: 10000`); a success toast stays at the 6 seconds the
   hook gives.
2. `FormDialog` and `ConfirmDialog` replace the `busy` prop with `action`, which takes the mutation (`error`,
   `isPending`, `reset`). They show `Alert tone="danger"` with `errorMessage(action.error, ...)` above the footer while
   `action.error` is set, set `loading` on the confirm button from `action.isPending`, and call `action.reset()` when
   the dialog closes and when it opens, so a stale error never greets the next opening.
3. Change the 38 call sites: `busy={x.isPending}` becomes `action={x}`. This is mechanical; `pnpm turbo run typecheck`
   fails on every one that was missed because `busy` no longer exists.
4. The two switches keep the optimistic move. On failure the switch returns to the server's state, and the failure
   shows as a toast (now above everything, per phase 1) naming the app and the server's sentence.
5. Tests for both dialogs: the error shows, clears on the next attempt, and is gone after close and reopen.

## Verification

- `pnpm --filter @suite/ui test`, `pnpm --filter @suite/remote-kit test`, and the remotes' own tests pass.
- `cd frontend && pnpm turbo run lint typecheck test build` passes.
- Redeploy, then in `13-interaction-audit.spec.ts` (every check uses Playwright `route` to slow or fail one request):
  - a switch whose `PUT` is delayed 1800ms keeps opacity 1 and `aria-busy`, shows the spinner, and its bounding box
    does not change;
  - the customer Edit dialog with a `500` problem response shows the server's sentence inside the dialog and a toast
    whose centre point is on top; closing and reopening the dialog shows no error;
  - the invoice sheet with its detail `GET` answering `500` shows an alert with the sentence and Retry in the Items
    section, never the empty mark; after un-routing, Retry shows the items;
  - the invoices list with its `GET` answering `500` shows the server's sentence;
  - a list `GET` delayed 800ms after a filter change shows the progress bar within 300ms and none for an immediate
    answer;
  - the width of the dialog's Save button is identical before and during loading.
- `12-screen-audit.spec.ts` still passes: it forces a slow and a failing response on each list.

## Risks / rollback

- Replacing `busy` with `action` is a breaking prop change inside the monorepo only; the typecheck is the safety net.
- `QueryState` must not hide a query that failed but has stale data: the failed-refetch test covers it.
