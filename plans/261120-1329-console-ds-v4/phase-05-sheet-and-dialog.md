---
phase: 5
title: "Sheet and Dialog as bottom sheets"
status: done
priority: P1
effort: "2d"
dependencies: [3]
---

# Phase 5: Sheet and Dialog as bottom sheets

## Goal
The record sheet is a side sheet with a scrim on tablets and desktops and a draggable bottom sheet on phones. It leads with the record's key figure, puts its actions under the title (docked at the bottom on phones), and never cuts the table. Dialogs become bottom sheets on phones with stacked full-width buttons.

## Read first
- `reference/design-system-v4/components/Sheet/README.md`, `Dialog/README.md`, `BulkBar/README.md`.
- `reference/design-system-v4/components/bundle.js`: `useDragDismiss`, `Sheet`, `Dialog`.
- `reference/design-system-v4/components/bundle.css`: from `.sc-sheet {` to `.sc-dragging`.
- `reference/targets/Sheet-dark-1000.png`, `InvoicesScreen-light-1280.png`, `InvoicePeekPhone-light-390.png`, `InvoicePeekPhone-dark-390.png`.
- acceptance.md checks D1 to D4, M5.

## Files to create / modify
- Create: `frontend/packages/ui/src/use-drag-dismiss.ts`, `frontend/packages/ui/src/use-drag-dismiss.test.ts`
- Modify: `frontend/packages/ui/src/sheet.tsx`, `frontend/packages/ui/src/dialog.tsx`, `frontend/packages/ui/src/bulk-bar.tsx`, `frontend/packages/ui/src/index.ts`
- Modify: `frontend/packages/remote-kit/src/record/record-sheet.tsx`, `frontend/packages/remote-kit/src/dialogs/form-dialog.tsx` (grid breakpoint only)

## Tasks

### Task 5.1: `useDragDismiss`
- Goal: dragging the handle or the head of a bottom sheet down closes it past 25% of its height or on a flick.
- Target files: create `frontend/packages/ui/src/use-drag-dismiss.ts` and its test.
- Steps:
  1. Port `useDragDismiss(panelRef, onClose, enabled)` from the reference `bundle.js` to TypeScript. It returns `{ onPointerDown, onPointerMove, onPointerUp, onPointerCancel }`. It ignores pointer-downs on `button, a, input, select, textarea`, sets `transform: translateY(<dy>px)` on the panel while dragging (only `dy >= 0`), and on release closes when `dy > panel.offsetHeight * 0.25`, or when `dy > 24` and the speed is above 0.5px/ms. Otherwise it animates back.
  2. When `enabled` is false, every handler does nothing.
  3. Test with Testing Library's `fireEvent.pointerDown/pointerMove/pointerUp` on a div whose `offsetHeight` is stubbed to 800 (`Object.defineProperty`):
     - a drag of 300px calls `onClose` once;
     - a drag of 100px over 1000ms (advance a fake clock with `vi.useFakeTimers` and `vi.setSystemTime`) does not call it;
     - with `enabled=false` a drag of 300px does not call it.

     `setPointerCapture` does not exist in jsdom; stub it on `HTMLElement.prototype`.
- Success criteria: the three cases behave as stated.
- Verify: `pnpm --filter @suite/ui test -- use-drag-dismiss` exits 0.

### Task 5.2: Sheet: side or bottom, scrim, actions, figure
- Goal: the sheet matches the reference on all three widths.
- Target files: `frontend/packages/ui/src/sheet.tsx` (`SheetProps`, `Sheet`).
- Steps:
  1. New props:
     - `actions?: React.ReactNode`: the primary action, up to one outline action, then a "…" menu;
     - `figure?: boolean`: the title is the key figure;
     - `placement?: 'auto' | 'side' | 'bottom'` (default `auto`).

     Change the default of `modal` to `true`.
  2. `bottom = placement === 'bottom' || (placement === 'auto' && useMedia(PHONE_QUERY))`.
  3. Overlay: render `DialogPrimitive.Overlay` with `fixed inset-0 z-40 bg-overlay backdrop-blur-[2px]` when `modal`.
  4. Content classes:
     - side: `fixed inset-y-2 right-2 z-50 flex w-[calc(100%-1rem)] max-w-[460px] flex-col rounded-xl bg-popover shadow-lg`;
     - bottom: `fixed inset-x-0 bottom-0 z-50 flex h-[88dvh] flex-col rounded-t-[20px] bg-popover shadow-xl pb-[env(safe-area-inset-bottom)]`.

     Attach `useDragDismiss(ref, onClose, bottom)` to a grab handle (`mx-auto mt-2 h-[5px] w-10 shrink-0 rounded-full bg-input touch-none`, rendered only when `bottom`) and to the `<header>` (add `touch-none` when `bottom`).
  5. Header, in order:
     - a row with the eyebrow (`text-small text-muted-foreground`) on the left, and on the right the previous/next buttons (hidden when `bottom`) and the close button. All icon buttons are `size-8`, or `size-11` when `bottom` (M2);
     - the title: `text-heading` normally, or `text-metric tabular-nums` with `figure`;
     - the description in a `flex flex-wrap items-center gap-2 text-small text-muted-foreground` row;
     - when not `bottom`, `actions` in `mt-2 flex flex-wrap gap-2`.
  6. Body: `flex-1 overflow-y-auto overscroll-contain p-5` (`p-4` when `bottom`), with `flex flex-col gap-6`.
  7. When `bottom` and `actions` exist, render a dock after the body: `flex gap-2 border-t border-border bg-popover px-4 py-3`, and inside it `[&_button]:h-11 [&_button]:flex-1 [&_button]:text-[15px]` with `[&_button[aria-label]]:flex-none [&_button[aria-label]]:w-11` for icon-only buttons.
  8. Keep `footer` rendered at the very bottom as today, for callers that still pass one.
- Success criteria: side sheet with scrim on desktop; bottom sheet with handle and dock on phones.
- Verify: `pnpm --filter @suite/ui test -- sheet` exits 0. Add tests:
  - with `placement="bottom"` the dialog element has the class `rounded-t-[20px]` and the actions render once, inside the dock;
  - with `placement="side"` the actions render in the header.

### Task 5.3: RecordSheet passes the new props and closes on the scrim
- Goal: the record sheet closes on the scrim (D1) but not when the person clicks inside a child dialog.
- Target files: `frontend/packages/remote-kit/src/record/record-sheet.tsx`.
- Steps:
  1. Add `actions`, `figure` and `placement` to `RecordSheetProps` and pass them through.
  2. Replace `onInteractOutside={(event) => event.preventDefault()}` with a handler that calls `event.preventDefault()` only when `(event.target as Element | null)?.closest('[role="dialog"], [role="alertdialog"], [data-radix-popper-content-wrapper]')` is not null. Otherwise Radix closes the sheet.
- Success criteria: clicking the scrim closes the sheet; clicking inside a confirmation opened from the sheet does not.
- Verify: `pnpm --filter @suite/remote-kit test` exits 0. Add a test: render a `RecordSheet` with a child `Dialog` open; a pointer-down inside the child dialog does not call `onClose`; a pointer-down on `document.body` calls it.

### Task 5.4: Dialog sizes and the phone bottom sheet
- Goal: dialogs are 520px (640px for `lg`) and become bottom sheets on phones.
- Target files: `frontend/packages/ui/src/dialog.tsx` (the `Content` classes and footer).
- Steps:
  1. Width: `max-w-[520px]`, or `max-w-[640px]` for `size === 'lg'`, replacing `max-w-md` and `max-w-2xl`.
  2. Phone classes on the same element:
     - `max-md:inset-x-0 max-md:bottom-0 max-md:top-auto max-md:left-0 max-md:w-full max-md:max-w-none max-md:translate-x-0 max-md:translate-y-0`;
     - `max-md:rounded-b-none max-md:rounded-t-[20px] max-md:max-h-[88dvh] max-md:pt-7 max-md:pb-[calc(1.25rem+env(safe-area-inset-bottom))]`.
  3. A grab handle, `md:hidden absolute top-2 left-1/2 -ml-5 h-[5px] w-10 rounded-full bg-input touch-none`, wired to `useDragDismiss(ref, onClose, phone)` with `phone = useMedia(PHONE_QUERY)`; the title block also gets the handlers.
  4. Footer: `mt-5 flex justify-end gap-2 max-md:flex-col-reverse max-md:[&_button]:h-11 max-md:[&_button]:w-full`.
- Success criteria: on a 390px viewport the dialog sits at the bottom with full-width buttons.
- Verify: `pnpm --filter @suite/ui test -- dialog` exits 0.

### Task 5.5: BulkBar docks on phones; form dialogs go to one column
- Goal: the bulk bar spans the bottom on phones, and two-column dialogs collapse below 768px.
- Target files: `frontend/packages/ui/src/bulk-bar.tsx` (root `className`); `frontend/packages/remote-kit/src/dialogs/form-dialog.tsx` line 62.
- Steps:
  1. Add `max-md:fixed max-md:inset-x-3 max-md:bottom-[calc(0.75rem+env(safe-area-inset-bottom))] max-md:justify-between` to the BulkBar root.
  2. In `form-dialog.tsx`, change `sm:grid-cols-2` to `md:grid-cols-2`.
- Success criteria: no layout change on desktop.
- Verify: `pnpm --filter @suite/ui test` and `pnpm --filter @suite/remote-kit test` exit 0.

## Todo
- [x] Task 5.1: `useDragDismiss`
- [x] Task 5.2: Sheet: side or bottom, scrim, actions, figure
- [x] Task 5.3: RecordSheet passes the new props and closes on the scrim
- [x] Task 5.4: Dialog sizes and the phone bottom sheet
- [x] Task 5.5: BulkBar docks on phones; form dialogs go to one column
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
