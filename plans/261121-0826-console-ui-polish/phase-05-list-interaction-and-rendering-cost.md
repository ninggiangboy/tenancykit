---
phase: 5
title: "List interaction and rendering cost"
status: completed
priority: P2
effort: "1.5d"
dependencies: [1]
---

# Phase 5: List interaction and rendering cost

## Goal

A list answers the person's hands (search, filter) without a request per key or a dead click, and the console stops
paying for work it does not need on every render. Fixes E10 and E11, and removes the causes of cost the code shows. It
then measures; it does not claim the felt lag is gone.

## Files to Create / Modify

- Modify: `frontend/packages/remote-kit/src/list/list-toolbar.tsx`
- Create: `frontend/packages/remote-kit/src/list/use-debounced-callback.ts` (+ test) if the toolbar needs more than a few
  lines for it; otherwise keep it inside the toolbar
- Modify: `frontend/packages/ui/src/faceted-filter.tsx`
- Modify: `frontend/packages/ui/src/use-media.ts` (+ `use-media.test.ts`)
- Modify: `frontend/packages/ui/src/data-table.tsx` (the keyboard listener)
- Modify: `frontend/packages/ui/src/command-palette.tsx` (`useHotkey`)
- Modify: `frontend/packages/ui/src/skeleton.tsx` and `styles/theme.css` (the `shimmer` keyframe)
- Modify: `frontend/e2e/support/audit.ts` (a `frameStats` helper) and `frontend/e2e/tests/13-interaction-audit.spec.ts`
- Create: `plans/261121-0826-console-ui-polish/reports/performance-before-after.md`

## Tasks & Steps

### 5.1 Search sends a request when the person pauses
1. `ListToolbar` keeps a `draft` for the box (initialised from `list.q`), updates it on every key, and calls `list.setQ`
   250ms after the last key. Enter and blur call it at once; Escape clears the draft and calls it at once.
2. When `list.q` changes from outside (the Clear button, the filter chip's remove, the browser's Back), the draft takes
   the new value, unless the person is typing (the box has focus and the draft differs from the last value it sent).
3. The box never reads its value from the URL while typing, so the caret cannot jump.
4. Test (fake timers): five keys inside 250ms call `setQ` once with the last text; Enter calls it at once; an external
   change to `q` replaces the draft; focus and typing block that replacement.
5. Out of scope here and recorded for later: `urlState.set` builds the next search from the `search` captured at render,
   so two `set` calls in one tick lose the first. The toolbar no longer makes that likely.

### 5.2 A facet option toggles from its whole row
1. In `faceted-filter.tsx` each option row becomes a `<label>` that contains the checkbox, the icon, the text and the
   count, with the hover and focus styling on the label. Today the row has `cursor-pointer` and `hover:bg-accent` on a
   `div` that has no click handler, so only the 16px box responds.
2. Keep the checkbox's `aria-label`; keep the phone's 44px row height (`PHONE_ROW`).
3. Test (`@suite/ui`): clicking the text, the count and the box each toggle once.

### 5.3 Subscriptions and listeners that were renewed on every render
1. `useMedia`: `subscribe` is a new function every render, so `useSyncExternalStore` unsubscribes and resubscribes each
   time, and `matchMedia(query)` builds a new list on every `getSnapshot`. Cache one `MediaQueryList` and one stable
   `subscribe` per query string at module level. Test: ten re-renders add one listener.
2. `DataTable`: the `keydown` listener is removed and added again whenever `rows` or the active row changes, because
   `onKeyDown` is in the effect's dependencies. Keep the latest handler in a ref and register the listener once. Same
   for `useHotkey` (the handler is in its dependencies, and `ListToolbar` passes a new arrow every render). Test: ten
   re-renders call `document.addEventListener('keydown', ...)` once.

### 5.4 A placeholder that does not repaint
1. `Skeleton` animates `background-position`, which repaints on the main thread for every placeholder, and a table shows
   25 at once. Draw the highlight as a child translated with `transform` (compositor only), clipped by `overflow-hidden`
   on the skeleton, and keep the same colours. Reduced motion shows the plain block.
2. The five rows and the section placeholders look the same as before in the screenshots of `10-visual`.

### 5.5 Measure
1. Add `frameStats(page, action)` to `support/audit.ts`: it samples `requestAnimationFrame` deltas and `longtask` entries
   around `action` and returns `{ frames, worstFrameMs, framesOver33ms, framesOver50ms, longTasks }`.
2. The "before" numbers below were measured on 2026-11-21 with the same sampling (see `plan.md`), so only the "after"
   is measured. Record both in `reports/performance-before-after.md`, in headless Chromium at 1280x800, once phases 1, 2
   and this one are done:
   - opening a record sheet (baseline: worst 33ms, 4 frames over 33ms);
   - scrolling a long list by twelve 250px wheel steps (baseline: worst 17ms);
   - switching theme (baseline: worst 17ms);
   - typing an 8-character search word (baseline: 8 list requests, worst frame 17ms).
3. Acceptance for the headless numbers: no frame over 50ms, at most 2 frames over 33ms, no long task, and at most 2 list
   requests for the typed word.
4. Headless Chromium has no GPU, so it under-reports. The owner records a Chrome Performance trace on real hardware for
   the same four actions before and after, and the report says what the trace shows. If the console still feels slow,
   the trace names the cause and a new plan is opened for it. This plan does not call lag fixed on headless numbers.

## Verification

- `pnpm --filter @suite/ui test` and `pnpm --filter @suite/remote-kit test` pass, with the new tests above.
- `cd frontend && pnpm turbo run lint typecheck test build` passes.
- Redeploy; in `13-interaction-audit.spec.ts`: typing a word of 8 characters at 40ms per key on invoices sends at most 2
  list requests and the box ends with the full word; clicking the text of a Customers status option adds the chip and
  `f_status` to the URL; the four `frameStats` scenarios meet the acceptance numbers above.
- `10-visual.spec.ts` and `12-screen-audit.spec.ts` still pass.

## Risks / rollback

- A debounced box that ignores a quick Enter would feel broken: the Enter flush is tested.
- Each change is local to one file; any of them can be reverted alone.
