---
phase: 2
title: "Sheet presence and leave motion"
status: completed
priority: P1
effort: "1.5d"
dependencies: [1]
---

# Phase 2: Sheet presence and leave motion

## Goal

Every record sheet, the bulk bar and the unsaved bar stay mounted long enough to play their leave animation, and the
full-viewport blur that made every sheet open expensive is gone. Fixes E5.

## Why it fails today

A screen renders `{record.selected && <XSheet ... />}` and the sheet passes `open` (always true) to `RecordSheet`. When
the record clears, React removes the sheet in the same commit, so Radix has no chance to run `animate-slide-out-right`
(`data-[state=closed]`). The leave animations exist in `theme.css` and in `sheet.tsx`; nothing ever gets to play them.
The bulk bar and the unsaved bar return `null` at zero, which has the same effect.

## Files to Create / Modify

- Create: `frontend/packages/ui/src/use-presence.ts` and `use-presence.test.ts`
- Modify: `frontend/packages/ui/src/index.ts` (export `usePresence`)
- Modify: `frontend/packages/ui/src/{sheet,bulk-bar,unsaved-bar}.tsx`
- Modify: `frontend/packages/remote-kit/src/list/use-record-selection.ts` (+ a test)
- Modify: `frontend/packages/remote-kit/src/record/record-sheet.tsx` (+ `record-sheet.test.tsx`)
- Modify, Platform (5 screens): `frontend/remotes/platform/src/features/{tenants,users,roles,systems,remotes}/page.tsx` and
  their `components/*-sheet.tsx`
- Modify, Billing (13 screens), pages and sheet components: `customers`, `invoices`, `payments`, `plans`, `coupons`,
  `subscriptions` (each has `components/*-sheet.tsx`), and the inline sheets in `charges/page.tsx`,
  `reservations/page.tsx`, `refunds/page.tsx`, `wallets/page.tsx`, `webhooks/page.tsx`, `redemptions/page.tsx`,
  `usage/components/usage-events.tsx` (all under `frontend/remotes/billing/src/features/`)
- Modify: `frontend/e2e/tests/13-interaction-audit.spec.ts`

## Tasks & Steps

### 2.1 `usePresence`
1. In `@suite/ui` write `usePresence<T>(value: T | null | undefined, exitMs = 180): { shown: T | null; present: boolean }`.
   `shown` is `value` while it is set, and the last non-null `value` for `exitMs` after it becomes null; then it is
   `null`. `present` is `shown !== null`. A new non-null value replaces `shown` immediately and cancels the timer. Clear
   the timer on unmount.
2. `exitMs` defaults to the `--duration-base` value (180ms, the same constant `use-drag-dismiss.ts` calls `LEAVE_MS`).
   Export one constant from `motion.ts` (`LEAVE_MS = 180`) and use it in `use-drag-dismiss.ts`, `toaster.tsx` and here, so
   the three cannot drift.
3. Reduced motion: when `matchMedia('(prefers-reduced-motion: reduce)')` matches, `exitMs` is 0.
4. Tests (`vitest` with fake timers): holds the last value for `exitMs`; a new value during the wait wins; unmount
   clears the timer; reduced motion returns `null` at once.

### 2.2 The selection hook
1. `useRecordSelection` returns, besides what it returns now, `shown` (from `usePresence(selected)`) and `isOpen`
   (`selected !== undefined`). `prev` and `next` still work from `selected`.
2. Test: selecting, clearing and re-selecting returns the right `shown` and `isOpen` at each step.

### 2.3 The sheet components and the screens
1. Each `*-sheet.tsx` gets one new prop, `open: boolean`, and passes it to `RecordSheet` in place of the literal `open`.
   An inline `<RecordSheet open ...>` in a page gets `open={record.isOpen}`.
2. Each screen changes from `{record.selected && <XSheet x={record.selected} .../>}` to
   `{record.shown && <XSheet x={record.shown} open={record.isOpen} .../>}`.
3. A sheet's own child dialogs (`useState<'edit' | 'deactivate' | null>`) stay as they are. They are rendered next to the
   sheet, so they stay mounted for the same 180ms and close with it.
4. Do the Platform screens first, run the Platform e2e screens, then Billing, so a mistake in the pattern costs five
   files, not eighteen.
5. `RecordSheet` documents `open`: a screen keeps the sheet mounted while `open` is false.

### 2.4 The floating bars
1. `BulkBar` and `UnsavedBar` call `usePresence(count > 0 ? count : null)`. While `shown` is set but `count` is 0, render
   with `data-state="closed"` and `data-[state=closed]:animate-fade-out data-[state=closed]:pointer-events-none`; the
   entry stays `animate-rise-in`. Their label shows the last count during the leave, not "0 selected".

### 2.5 No blur on the scrim
1. Remove `backdrop-blur-[2px]` from the sheet scrim in `sheet.tsx`. The scrim colour (`bg-overlay`) is enough, matching
   the dialog and the palette. This is the only full-viewport blur in the console and it re-rasterises under every frame
   of the slide.

### 2.6 Checks
Add to `13-interaction-audit.spec.ts`, for every list screen that has rows:
1. Open the first row's sheet, click Close, and sample the DOM at 20ms steps for 400ms: the sheet's `role="dialog"` must
   be present at 20ms with `data-state="closed"` and `opacity` below 1 within 120ms, and gone by 300ms. Assert the same
   for Escape and for a click on the scrim.
2. After the sheet is gone, `document.activeElement` is the row (or the button that opened it): the focus rule of the
   design system.
3. Select two rows where a screen has bulk actions (none do today; keep the check for the first one that does) and clear
   them: the bar leaves with `data-state="closed"` first. If no screen has a bulk action, mount the component in a unit
   test in `@suite/ui` instead.
4. The 390px version: close by drag (the drag `11-mobile.spec.ts` performs on the sheet handle; move it into
   `support/audit.ts` so both specs share it) and by the Close button.

## Verification

- `pnpm --filter @suite/ui test`, `pnpm --filter @suite/remote-kit test` pass, including the new hook tests.
- `cd frontend && pnpm turbo run lint typecheck test build` passes (typecheck proves every sheet call site got `open`).
- Redeploy and run `13-interaction-audit.spec.ts`, `11-mobile.spec.ts`, `12-screen-audit.spec.ts`.
- `grep -rn "<RecordSheet" frontend/remotes | grep -v "open="` returns nothing.

## Risks / rollback

- A sheet that is closed but still mounted for 180ms must not trap focus or swallow clicks: Radix sets `pointer-events`
  on the body only while open. The focus-return check in 2.6 covers it.
- Rolling back is reverting the screens' two lines; the hook and the bars are independent.
