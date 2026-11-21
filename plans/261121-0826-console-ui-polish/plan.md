---
title: "Suite Console polish: frame, overlays, states, interaction and one backend precondition fix"
description: "Fix what a full review of the console found: a page that scrolls instead of its table, overlays that hide each other, sheets that leave without a leave animation, writes and failures that show no state, controls that do nothing when clicked, and one backend rule that makes the first entitlement toggle fail."
status: completed
priority: P1
effort: "7 phases, about 10 person-days"
branch: dev
tags: [frontend, backend, bugfix, design-system, performance]
blockedBy: []
blocks: []
created: 2026-11-21
---

# Suite Console polish

## Overview

The console follows design system v4 (`docs/console.md`), and its screenshot audit (`frontend/e2e/tests/12-screen-audit.spec.ts`) reports
0 findings. A manual review on 2026-11-21, with measurements taken by driving the deployed console in Chromium, found
that the audit measures sizes, colours and the *opening* of overlays and nothing about layering, scrolling, leaving,
failing or saving. This plan fixes what the review found, and extends the audit so the same faults cannot come back.

The owner allowed backend changes where needed. One is needed: the app-entitlement `PUT` rejects the precondition the
entitlement `GET` itself tells the client to send.

## Evidence (every item was observed, not assumed)

| Id | Finding | How it was observed | Phase |
|---|---|---|---|
| E1 | A long table scrolls the whole document, not the table; the header and the column titles leave the screen | At 1280x800 the invoices page: `document.scrollHeight` 1069, `main` does not scroll. Cause: `min-h-dvh` on the frame (`ShellApp.tsx`) | 1 |
| E2 | The sidebar hides "Other apps" and the account row with the sign-out button | The sign-out button sits at y=908 on an 800px window. Cause: the whole `<aside>` scrolls (`sidebar.tsx`) | 1 |
| E3 | A menu opened from inside a sheet renders behind it | Menu `z-30`, sheet `z-50`; `elementFromPoint` at the menu's centre is not the menu | 1 |
| E4 | A toast raised while a sheet is open is covered by the sheet | The toast (`z-50`, bottom right) overlaps the sheet (`z-50`, later in the DOM) | 1 |
| E5 | A sheet vanishes the instant it closes | 18 screens render `{selected && <XSheet open />}`; the sheet is gone from the DOM after 1 ms | 2 |
| E6 | A list loading or refreshing shows no sign of it after the first load | `keepPreviousData` keeps the old rows; only `isLoading` is passed to the table | 4 |
| E7 | A failed request inside a sheet reads as "nothing here" | Six sections handle `isLoading` only; an invoice detail that fails shows "—" under Items | 4 |
| E8 | A failed list says "Could not load this" and drops the server's sentence | `ErrorPanel` reads `error` only to spot a 403 | 4 |
| E9 | A switch that saves goes dim like a disabled control, and a write that fails is a toast only, which E4 hides | Opacity 0.5 for the whole request; failure snapshot shows the switch snapping back | 4 |
| E10 | Typing in a list's search box sends one request per key | 8 characters, 8 list requests | 5 |
| E11 | Clicking a facet option's label does nothing; only the 16px box toggles | No chip and no URL change after clicking "Active" | 5 |
| E12 | The first toggle of an app that a tenant never changed fails with 412 | `PUT` sends `If-Match: "0"` (the version `GET` reported); `ExpectedVersion.checkAbsent()` accepts only no header or `*` | 3 |
| E13 | Icons say the wrong thing or nothing | The theme button is a sparkle and its items are sparkle, dashed circle and gear; Edit and Rename use a gear; the half-checked box shows an X | 6 |
| E14 | Misalignment | Version rows are 57px (active) and 53px (others); detail labels and values are 1 to 2px apart; the ledger row crowds its action | 6 |

Not reproduced: lag. In headless Chromium, 1280x800, the worst frame was 33 ms while opening a sheet (4 frames over
33 ms), 17 ms while scrolling twelve 250px wheel steps and 17 ms while switching theme, and no long task ran. These are
the "before" numbers for phase 5. Phase 5 removes the causes the code shows (full-viewport blur, shimmer that
repaints, a listener per render, a request per key) and measures before and after; it does not claim the felt lag is
gone without a profile on a real GPU.

## Outcome contract

**Done means**
- At 768px and wider the frame is the viewport: the top bar and the sidebar stay, `main` scrolls, a table's column
  titles stay under the top of `main`, and the sidebar keeps "Other apps" and the account row in view. Phones keep
  document scrolling with the sticky top bar.
- One z-index scale decides which layer wins. A menu, popover, tooltip or combobox opened from a sheet or dialog is above
  it; a toast is above everything.
- Every sheet, the bulk bar and the unsaved bar leave with the leave animation, and focus returns where it came from.
- Every write shows three states: in progress (a spinner, never a dimmed control or a width jump), done (as today), and
  failed with the server's sentence next to what was being done (inside the dialog, or at the switch), not only in a
  toast.
- Every read inside a sheet shows loading, failed (with the server's sentence and Retry) or empty, and a failure never
  looks like empty. A list that refreshes shows it is refreshing.
- A search box sends at most two requests for a typed word. A facet option toggles from its whole row.
- Enabling an app for a tenant for the first time succeeds from the console.
- Icons and alignment issues in E13 and E14 are fixed, and the strings the review found hard-coded in English are in the
  catalogues.
- The audit has checks for each rule above, and fails on any regression. `pnpm turbo run lint typecheck test build` and
  the end-to-end suite pass.

**Out of scope**: new screens, new apps, server-side per-field validation errors (the backend answers `detail` only, so a
failed form shows that sentence, not a mark on one field), moving the console to another UI library, full-page record
views, changing design system tokens.

**Constraints (carried over from `docs/console.md`)**: peek sheets keep their own URLs; screens declare and the kit
decides (no viewport checks in screen code); data never animates and reduced motion zeroes every duration; spacing is a
4px step and a control's height comes from `--size-control-*`; `cn()` must know every `text-<name>` size; generated
`tokens.css` and `packages/api-client/src/generated` are never edited by hand; the shell owns the CSS variables.

## Decisions taken in this plan

1. **Backend fix lives in `ExpectedVersion`, not in the console.** The entitlement list reports an app a tenant never
   changed as version 0 (`EntitlementController`: "disabled at 0"), so the API's own contract tells a client to send
   `If-Match: "0"`. `checkAbsent()` then refuses it, because it accepts only no header or `*`. The rule becomes: version
   0 names a row that does not exist, so a precondition of `"0"` (alone or in a list) may create it. Any other tag on a
   missing row is still 412 with no current ETag. The change is a loosening for every endpoint that creates through
   `checkAbsent()` (Platform entitlements, Loyalty program); an existing row has version 1 or more, so `"0"` still
   fails there. The rejected alternative, omitting the header in the console when the version is 0, would leave the API
   contradicting itself for every other client.
2. **Native `<select>` stays, restyled.** A native select gives phones the native picker and keeps every form binding. It
   gets the Combobox's border, height, font and chevron. No new dependency.
3. **Desktop frame is fixed, phone frame is not.** Below 768px the document scrolls (mobile browsers collapse their
   address bar only for document scrolling), above it `main` scrolls. A table keeps horizontal scrolling only if a
   screen cannot fit at 768px; then that table loses its sticky header.
4. **Sheets stay mounted for the leave animation through a presence hook**, not through a new sheet API on every screen:
   `usePresence` in `@suite/ui`, used by `useRecordSelection` and by the two floating bars.
5. **Failures are shown where the person is looking**, and the toast stays as the second channel. An error toast lasts 10
   seconds, a success toast 6.
6. **Refetch scope.** `useAction` already refetches only mounted queries. It stays as the default; the two switch hooks
   (tenant apps and remote enable) pass the queries of their own family (matched by the generated `_id`) so a toggle does not
   reload unrelated lists.

## Phases

| # | Phase | Depends on | Effort | Status |
|---|---|---|---|---|
| 1 | [Shell frame, scrolling and the layer scale](./phase-01-shell-frame-and-layers.md) | none | 1.5d | completed |
| 2 | [Sheet presence and leave motion](./phase-02-sheet-presence-and-leave-motion.md) | 1 | 1.5d | completed |
| 3 | [Backend: a precondition of 0 creates an absent row](./phase-03-backend-first-write-precondition.md) | none (parallel with 1, 2) | 0.5d | completed |
| 4 | [Write, read and failure states](./phase-04-write-read-and-failure-states.md) | 1, 2 | 2.5d | completed |
| 5 | [List interaction and rendering cost](./phase-05-list-interaction-and-rendering-cost.md) | 1 | 1.5d | completed |
| 6 | [Icons, alignment and consistency](./phase-06-icons-alignment-and-consistency.md) | 2, 4 | 2d | completed |
| 7 | [Audit coverage, docs and the full run](./phase-07-audit-coverage-docs-and-full-run.md) | 1 to 6 | 1d | completed |

Order: 3 can run at any time. 1 then 2 then 4, because 2 and 4 both edit the screens' sheet files and 6 edits them again.
Each phase adds its own checks to `frontend/e2e/tests/13-interaction-audit.spec.ts` (created in phase 1), so a phase is
not done until the check that would have caught its fault passes.

## How to work (read before a phase)

- Read `docs/console.md` first: the layout, the motion rules and the two size rules (root font 14px with `--spacing`
  4px; `TYPE_SCALE` in `cn()`).
- Run the narrowest test first (`pnpm --filter @suite/ui test`, `pnpm --filter @suite/remote-kit test`), then
  `pnpm turbo run lint typecheck test build` from `frontend/` when a shared package changed.
- A new class that leaves a remote's stylesheet order-dependent is a bug: use positive `md:` classes in shell code, as
  the comment in `ShellApp.tsx` explains.
- Comments, test names and commit messages describe behaviour. They never carry a plan, phase or finding id.
- Commits: conventional format, one concern each, no secrets, no `.env`.
- The end-to-end suite needs the compose stack up and the shells and remotes redeployed after a build (see "Running and
  testing" in `docs/console.md`). Phase 3 also needs the Platform image rebuilt.
- Before phase 1, capture the baseline, which also restores the screenshots the review's runs emptied:
  `AUDIT_STRICT=0 AUDIT_DIR=<a folder outside the repository> pnpm --dir frontend --filter @suite/e2e exec playwright test
  tests/10-visual.spec.ts tests/12-screen-audit.spec.ts`. Phase 7 compares against it.
- Do not delete `frontend/e2e/test-results/` by hand. Playwright empties its output directory on every run; keep any
  capture you want to compare elsewhere (`AUDIT_DIR`).

## Risks

| Risk | Mitigation |
|---|---|
| Fixing the frame breaks sticky positioning (a scrolling `overflow-x-auto` wrapper inside `main` stops `thead` from sticking) | Phase 1 replaces the wrapper's overflow with `overflow-x-clip` at 768px and wider, and checks every list at 768, 900, 1024 and 1280; a table that cannot fit keeps `overflow-x-auto` and no sticky header |
| 18 sheet screens change together in phase 2 | The change is one hook and one prop per screen; typecheck proves every call site; the audit check opens and closes every sheet |
| Loosening `checkAbsent()` alters Loyalty | Loyalty tests run in phase 3; the change only adds an accepted value (`"0"`) on a missing row |
| 38 dialogs get an `error` prop in phase 4 | One line each (`error={x.error}` beside `busy={x.isPending}`); typecheck and the audit's failing-write check cover them |
| Felt lag stays after phase 5 | Measurements are recorded before and after in `reports/`; a Chrome performance trace on real hardware is a named step, and an unmoved number is reported, not explained away |

## Validation log

Session 1, 2026-11-21. The owner confirmed all three recommended answers:

| Question | Answer | Effect on the plan |
|---|---|---|
| Where the 412 on a first entitlement toggle is fixed | In `ExpectedVersion`: a precondition of `"0"` may create an absent row | Decision 1 stands; phase 3 as written |
| Phone scroll model | Keep document scrolling below 768px | Decision 3 stands; phase 1 changes `md:` and up only |
| How long an error toast lasts | 10 seconds | Decision 5 stands; phase 4.5 |

Verification pass: the plan's file paths, symbols and numbers were checked against the code with grep and by reading the
files (the frame, sidebar, sheet, data table, toast, switch, `useAction`, the entitlement controller, use case and
`ExpectedVersion`, and the Platform and Loyalty conditional-update tests). One claim was corrected in place: the coupon
sheet holds a checkbox in a confirmation, not a switch that writes, so only two switches are in phase 4. No separate
red-team agents were run; the risks table is the author's own and the phase checks are the independent test of the plan.

### Whole-plan consistency sweep

Re-read `plan.md` and the seven phase files after the answers: the three answers match what the files already say, the
phase numbers and dependencies agree between the index and each phase, every phase's checks live in
`13-interaction-audit.spec.ts`, and no phase edits a file another phase owns at the same time (phases 2, 4 and 6 touch
the same sheet files and are ordered 2, 4, 6 for that reason; phase 3 is disjoint from the rest).

## Delivery notes

Delivered on 2026-11-21 on branch `dev`. The evidence is in `reports/`: `phase-NN-before.md` (each rule's check failing on
the build before its fix), `performance-before-after.md` and `phase-07-full-run.md`. Where the delivery differs from
what the phases say, and why:

- **E6 premise was wrong.** `plan.md` said the list queries use `keepPreviousData`; only the option lookups did. A filter
  change blanked the table (rows hidden, skeleton after 150ms). Phase 4 therefore also added
  `placeholderData: keepPreviousData` to the 17 list hooks, which is what makes "the rows stay while the bar runs" true.
- **Layer tokens** are in the `:root` block of `theme.css`, not inside `@theme`: a theme variable that nothing in a
  given stylesheet's own sources uses is not emitted, and the shell owns these variables for every remote.
- **Four tables opt out of sticky titles** (`stickyHeader={false}`): invoices, wallets, usage events and webhooks do not
  fit at 768, 900 or 1024px. The audit lists them and fails any table that is clipped while sticky.
- **49 dialog call sites** took `action` (21 `FormDialog` and the confirm dialogs, as typecheck found them), not 38.
- **Sheet focus return** needed its own code: a Radix dialog without a Trigger gives focus back to nothing, so `Sheet`
  remembers the element that had focus when it opened, and table rows are focusable by a click (`tabIndex={-1}`).
- **Menu roles changed**: theme, language and density items are `menuitemradio`, so four existing e2e files and one
  shell test that looked for `menuitem` were updated.
- **The 12-screen audit's floating-action selector** named the old `z-20` class; it now matches `animate-rise-in`.
- **The e2e harness** got two repairs that were not in the plan, both found by the full run: the cached platform token
  expired during a run longer than five minutes (`platformAdminToken` now renews after three), and `11-mobile` opened
  the phone drawer before the shell had settled its first route, which closes it.
- **Not done here**: the Chrome Performance trace on real hardware (the owner's step), and a commit (nothing was
  committed; the working tree also still holds the previous session's uncommitted edits).
- **Unrelated failures found**: 3 Platform and 30 Loyalty `app-boot` tests fail with "repository root not found" because
  `RepositoryPaths` looks for `contracts` and `platform` directories at the repository root and the code now lives under
  `backend/`. They fail the same way without this plan's changes.

## Open questions

None.
