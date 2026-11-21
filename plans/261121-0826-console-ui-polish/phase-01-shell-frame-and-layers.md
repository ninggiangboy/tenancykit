---
phase: 1
title: "Shell frame, scrolling and the layer scale"
status: completed
priority: P1
effort: "1.5d"
dependencies: []
---

# Phase 1: Shell frame, scrolling and the layer scale

## Goal

At 768px and wider the frame is exactly the viewport, `main` scrolls, a table's column titles stay visible, the sidebar
keeps its footer in view, and one z-index scale makes every popup open above the surface it was opened from. Fixes E1,
E2, E3 and E4.

## Files to Create / Modify

- Modify: `frontend/packages/shell-core/src/ShellApp.tsx` (`FrameLayout`)
- Modify: `frontend/packages/shell-core/src/frame/sidebar.tsx`
- Modify: `frontend/packages/shell-core/src/frame/header.tsx` (only the sticky classes, if the scroll move needs them)
- Modify: `frontend/packages/ui/src/styles/theme.css` (layer tokens)
- Modify: `frontend/packages/ui/src/{sheet,dialog,command-palette,dropdown-menu,faceted-filter,combobox,tooltip,toaster,bulk-bar,unsaved-bar}.tsx`
- Modify: `frontend/packages/ui/src/data-table.tsx` (wrapper overflow, sticky header)
- Modify: `frontend/packages/remote-kit/src/layout/page.tsx` (floating action layer)
- Create: `frontend/e2e/tests/13-interaction-audit.spec.ts`
- Modify: `frontend/e2e/support/audit.ts` (helpers the new spec shares)

## Tasks & Steps

### 1.1 The layer scale
1. In `theme.css` add layer tokens inside the existing `@theme` block and nowhere else:
   `--z-sticky: 5`, `--z-bar: 20` (floating action, bulk bar, unsaved bar), `--z-scrim: 40`, `--z-modal: 50`
   (sheet, dialog, palette, the open drawer), `--z-popper: 60` (menus, popovers, comboboxes, tooltips), `--z-toast: 70`.
   Use them in classes as `z-(--z-popper)`.
2. Replace every hard-coded `z-*` in the files listed above with the token that matches its role. Today: dropdown menu
   `z-30`, faceted filter `z-30`, combobox `z-[60]`, tooltip `z-50`, toaster `z-50`, scrims `z-40`, panels `z-50`,
   bulk and unsaved bars `z-40`, header `z-[5]`, floating action `z-20`.
3. Run `grep -rn "z-\[\|z-[0-9]" frontend/packages frontend/remotes --include=*.tsx` and make sure no class outside the
   scale is left (a test file or a comment is fine). The sidebar drawer's scrim and panel use `--z-scrim` and
   `--z-modal`.
4. Radix portals into `<body>`, and equal z-index is decided by DOM order. Because a popper is now above every modal
   and the toast above the popper, order no longer matters.

### 1.2 The fixed frame
1. `FrameLayout` (`ShellApp.tsx:210`): the outer element is `min-h-dvh` today. Make it `md:h-dvh md:overflow-hidden`
   (positive `md:` classes only; the comment in that file explains why) and keep `min-h-dvh` for phones.
2. The column that holds `<Header />` and `<main>` becomes `md:min-h-0`, and `<main>` keeps `min-h-0 flex-1
   overflow-auto`, which now works because its parent has a height.
3. On phones nothing changes: the document scrolls and the top bar stays sticky.
4. The sheet and dialog scrims already cover the viewport, so they need no change.
5. Check by hand at 1280x800 with a 13-row list that the document no longer scrolls (`document.scrollingElement`
   `scrollHeight` equals `innerHeight`) and `main` does.

### 1.3 Sticky column titles
1. `DataTable` wraps the table in `overflow-x-auto rounded-lg border`. An `overflow` other than `visible` makes that
   wrapper the scroll container for `position: sticky`, so a sticky `thead` would never stick to `main`.
2. At `md` and up use `overflow-x-clip` on the wrapper and give the `thead` cells `sticky top-0 z-(--z-sticky) bg-card`
   (the border under the header needs a `shadow-[inset_0_-1px_0_var(--border)]` because a sticky row's border does not
   travel with it). Keep `overflow-x-auto` below `md`.
3. Open every list screen at 768, 900, 1024 and 1280 wide. If a table needs horizontal scrolling at any of them, add a
   `stickyHeader={false}` prop to `DataTable`, default `true`, and set it on that screen only. The audit in 1.5 reports
   which screens needed it.
4. The rounded top corners must still clip: wrap the sticky cells' background with `first:rounded-tl-lg
   last:rounded-tr-lg` on the first and last header cell.

### 1.4 The sidebar keeps its footer
1. `sidebar.tsx`: the `<aside>` is `flex flex-col overflow-y-auto` today. Make the `<aside>` `overflow-hidden`, keep the
   brand row and the footer `shrink-0`, and make only the `<nav>` (the app row, its sections and "Other apps") the
   `min-h-0 flex-1 overflow-y-auto` region. Remove the two `mt-auto` margins that were pushing the footer down only when
   there was spare room.
2. The phone drawer is the same component, so it gets the same fix.
3. The rail (collapsed or tablet) keeps its toggle button pinned the same way.
4. Check by hand on a tenant with Billing open at 1280x800: the account row and sign-out are visible without scrolling
   anything, and "Other apps" is reachable by scrolling the nav only.

### 1.5 The first checks
1. Create `13-interaction-audit.spec.ts`. It provisions one tenant with `provisionTenantFixture` and `seedBilling` and
   `seedBillingRich` exactly as `12-screen-audit.spec.ts` does, signs in as the tenant admin and as the platform
   operator, and exposes the helpers below. Reuse `support/audit.ts` for captures; add to it, do not copy it.
2. Checks for this phase, each a test with a name that states the behaviour:
   - on every list screen at 1280x800: `document.scrollingElement.scrollHeight <= innerHeight`, `main.scrollHeight >=
     main.clientHeight`, and after scrolling `main` by 400px the first `th` is still within 8px of the top of `main`
     (skip a screen that set `stickyHeader={false}`, and list those in the test output);
   - on a Billing screen at 1280x800: the sign-out button's bounding box is inside the viewport;
   - a platform tenant's sheet: open "More actions", then `elementFromPoint` at the menu's centre must be inside the
     menu;
   - with a customer's sheet open, save its Edit dialog and assert the toast's centre point is inside the toast;
   - the same two checks at 390x844 for the phone's bottom sheet.
3. Run the new spec once before the fixes (it must fail on these four) and once after (it must pass). Keep the failing
   run's output in `plans/261121-0826-console-ui-polish/reports/phase-01-before.md`.

## Verification

- `pnpm --filter @suite/ui test` and `pnpm --filter @suite/shell-core test` pass; `cn.test.ts` still passes.
- `cd frontend && pnpm turbo run lint typecheck test build` passes.
- Redeploy (see `docs/console.md`, "Running and testing"), then `pnpm --dir frontend --filter @suite/e2e exec playwright
  test tests/13-interaction-audit.spec.ts` passes.
- `pnpm --dir frontend --filter @suite/e2e exec playwright test tests/10-visual.spec.ts tests/12-screen-audit.spec.ts`
  still pass: the frame change must not move any measured size.

## Risks / rollback

- A sticky header that does not stick is the likely failure: check `overflow` on every ancestor between `thead` and
  `main`. Rolling back is reverting `FrameLayout` and the `DataTable` wrapper; the layer tokens are harmless alone.
- Phone layout is untouched by design; if a phone screen changes in `10-visual`, a `md:` class leaked.
