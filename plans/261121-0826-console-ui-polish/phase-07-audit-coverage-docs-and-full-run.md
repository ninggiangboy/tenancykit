---
phase: 7
title: "Audit coverage, docs and the full run"
status: completed
priority: P2
effort: "1d"
dependencies: [1, 2, 3, 4, 5, 6]
---

# Phase 7: Audit coverage, docs and the full run

## Goal

The rules this plan restored are written down and enforced by the suite, so the same faults cannot return unnoticed, and
the whole delivery is proved on the real stack.

## Why the existing audit missed everything

`12-screen-audit.spec.ts` reported 0 findings while every fault in `plan.md` existed. It measures control heights, tap
targets, input size, a second highlighted nav item, a fixed button over content, motion when an overlay *opens*, focus
return, and the amount, id and message-key rules. It never closes an overlay and watches, never asks which of two
layers is on top, never measures what scrolls, never makes a request fail inside a sheet, and never exercises a control
beyond looking at it. Phases 1 to 6 each added their checks to `13-interaction-audit.spec.ts`; this phase makes that file
complete and keeps it honest.

## Files to Create / Modify

- Modify: `frontend/e2e/tests/13-interaction-audit.spec.ts` (organise and complete)
- Modify: `frontend/e2e/tests/12-screen-audit.spec.ts` (only if a shared helper moved)
- Modify: `frontend/e2e/support/audit.ts`
- Modify: `docs/console.md`
- Modify: `docs/concurrency-and-locking.md` (only if phase 3 left a gap)
- Create: `plans/261121-0826-console-ui-polish/reports/phase-07-full-run.md`

## Tasks & Steps

### 7.1 Complete the interaction audit
1. Group the file by rule, one `test.describe` each, and make sure every rule in `plan.md`'s outcome contract has a test
   whose name is the rule: the frame and its scrolling; the layer order; leaving; write progress; write failure; read
   failure inside a sheet; list refresh; search requests; facet row; first entitlement; icons; alignment; Vietnamese
   strings.
2. Each test names the screens it covered in its output, and a test that silently covered none fails ("no list had
   rows" must be an error, not a pass).
3. A deliberately broken build must fail: for each rule, locally revert its fix (stash the change), run the test, see it
   fail, restore. Record one result per rule in `reports/phase-07-full-run.md`. A check that cannot fail is removed or
   rewritten.
4. The `suite` workflow already runs the whole `@suite/e2e` project, so the new file is picked up; confirm its run time
   in the report and, if it adds more than five minutes, split the tenant provisioning so it is shared with the other
   specs through one fixture instead of repeated.

### 7.2 Write it down
1. `docs/console.md`, in place (it is 224 lines; the limit is 800):
   - "Layout": the frame is the viewport from 768px up and `main` scrolls; phones scroll the document; the sticky column
     titles and the `stickyHeader` opt-out.
   - A new "Layers" section with the scale (`--z-sticky` 5, `--z-bar` 20, `--z-scrim` 40, `--z-modal` 50, `--z-popper` 60,
     `--z-toast` 70) and the rule that a popup opened from a surface must be higher than it.
   - A new "States" section: a write shows progress (a spinner; a switch never dims; a button never changes width), a
     failure shows the server's sentence inside the dialog or section that was acting and as a toast, a read inside a
     sheet is loading, failed or empty and never failed-as-empty, and a refreshing list shows the bar.
   - The motion paragraph: sheets and the floating bars stay mounted through `usePresence` for the leave animation; the
     sheet scrim does not blur.
   - The audit paragraph: what `13-interaction-audit.spec.ts` proves and that a rule without a test is not a rule.
   - "Limits": the backend answers `detail` only, so a failed form shows that sentence and not a mark on one field.
2. Update the design-system notes only if a token or a component API changed (the layer tokens are the only addition;
   `tokens.json` is not touched).
3. After editing, check every claim against the code: the file names, the token names and the numbers.

### 7.3 The full run, on the real stack
1. Backend: `cd backend/libs/core && ./gradlew build`, `cd backend/services/platform && ./gradlew build`,
   `cd backend/services/loyalty && ./gradlew build`.
2. Frontend: `cd frontend && pnpm install --frozen-lockfile && pnpm turbo run lint typecheck test build`.
3. Stack: `docker compose up -d --build --wait`, then deploy the shells and remotes as `docs/console.md` describes
   ("Running and testing").
4. `pnpm --dir frontend --filter @suite/e2e test` passes in full, including `10`, `11`, `12` and `13`.
5. Capture the screenshots after the change with `AUDIT_DIR` pointing outside the repository and compare them with the
   baseline captured before phase 1 (`plan.md`, "How to work"): every screen, light and dark, 1280 and 390 wide. List
   each visible difference and say which phase caused it; an unexplained difference is a bug.
6. Fill `reports/performance-before-after.md` and run the real-hardware trace from phase 5; state plainly whether the
   felt lag is gone.

## Verification

- All commands in 7.3 exit 0; the report lists each command and its result.
- `reports/phase-07-full-run.md` holds: the revert-and-fail result of every rule, the e2e run time, the screenshot
  differences with their causes, and the performance numbers.
- `git diff dev -- frontend backend | grep -nE '^\+.*(phase [0-9]|\bE[0-9]{1,2}\b)'` prints nothing: no comment, test name
  or message carries a plan, phase or finding id.

## Closing

- Close the plan with `ak plan close` and write a journal entry (`/ak-journal`) covering the decisions that surprised us:
  the shared-helper change in the backend, and why the audit passed while the console was broken.
- Unresolved at closing, if any, go in the report, not in the code.
