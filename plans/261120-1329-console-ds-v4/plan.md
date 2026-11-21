---
title: "Suite Console DS v4: app accents, density, phone and tablet"
description: "Bring the console in frontend/ to design system v4: one accent per app, tinted status pills, a comfortable/compact switch, card lists and bottom sheets on phones, an icon rail on tablets, a launcher home, the known UI bugs fixed, and a screenshot and accessibility suite that proves it."
status: in review
priority: P1
effort: "11 phases, about 16 person-days"
branch: dev
tags: [frontend, design-system, mobile, accessibility]
blockedBy: []
blocks: []
created: 2026-11-20
---

# Suite Console DS v4

## Overview

The console works, but it looks unfinished: it is monochrome by design (DS v3), the code drifted from that design in places, there is no mobile layout, and a few visible bugs remain (a raw `shell.open` key, "Platform › Platform" breadcrumbs, "Nothing changed" on a read error, a checkbox column on every table). The owner rewrote the design system as **v4** and confirmed the target in an `/ak-advise` session on 2026-11-20 (memory: `console-ui-v2-decisions`).

This plan implements v4 in `frontend/` for the Platform and Billing remotes and the two shells. It is written for an executor that follows steps exactly: every task names its files, its steps and a check that is a comparison. **Read "How to work" below before starting a phase.**

## Sources of truth

1. `reference/design-system-v4/README.md`: the brand book (colour, type, layout, responsive rules, UX principles).
2. `reference/design-system-v4/acceptance.md`: the acceptance criteria. Each check has an id (T1, L5, M4…). Phases cite them.
3. `reference/design-system-v4/porting.md`: how tokens and components reach `@suite/ui`, including the CSS trap in rule 2.
4. `reference/design-system-v4/tokens.json`: the v4 tokens.
5. `reference/design-system-v4/components/bundle.css` and `bundle.js`: the **reference implementation** (React 18, plain JS). Port the behaviour and the CSS values; do not copy the file into the code.
6. `reference/targets/*.png`: what finished screens look like (desktop 1280, tablet 900, phone 390, light and dark).

The live design system is the artifact https://claude.ai/artifact/CXbCvJyZEvS7Zx2NcQHCFi (version 1795165481-3dca); `reference/` is a copy of it, so the executor does not need access to it.

## Outcome contract

**Done means**
- `@suite/ui` tokens are generated from the v4 `tokens.json`, and the token test passes.
- Inside an app, the shell root carries `data-app="<code>"`, and the primary button, the active nav item, links, focus, selected rows, the active saved view and the floating action button take that app's accent. Home has no `data-app`.
- Status badges are tinted pills with an icon and a word.
- The user menu has Comfortable / Compact; the choice persists and sets `data-density`.
- Below 768px: top bar, nav drawer, card lists, bottom sheets with drag-to-close, a floating main action, saved views as pills. From 768px to 1023px: a 56px icon rail.
- Home is a launcher with one card per enabled app.
- The bugs above are fixed, and an i18n test fails on any `t()` key missing from either catalogue.
- A Playwright screenshot suite covers every Platform and Billing screen at 1280, 900 and 390 wide, light and dark, comfortable and compact, with no console error, no unhandled HTTP error and no horizontal scroll at 390. axe reports no `color-contrast` or `target-size` violation.
- `docs/console.md` describes v4.

**Out of scope**: full-page record views, a KPI dashboard home, UIs for other apps, backend or API changes, server-side preference storage, separate mobile routes or mobile-only screen components.

**Constraints**: keep peek sheets with their own URLs; the design system is the source of truth (never edit generated `tokens.css`); the shell owns CSS variables; screens declare and the kit decides (no viewport checks in screen code); no new gesture library (no vaul); UTC times and sentence case stay.

## Decisions taken in this plan

- **Selection only with a real bulk action.** Today every list is selectable because `ResourceList` defaults to "copy IDs". v4 rule L5 says a checkbox column appears only when the list has bulk actions. Copying IDs is not one: each record's ID is a `CopyId` in its sheet. `ResourceList` becomes selectable only when a screen passes `bulkActions`. No screen passes one today, so no list shows checkboxes. The e2e checks that select rows are replaced by a check that no checkbox column exists.
- **Saved-view counts** use the existing list contract: one request per view with `limit=1` and `page_info=full`, reading `total`. No API change.
- **Launcher content** (purpose, description, shortcut links) comes from a static catalogue keyed by app code in `@suite/shell-core`, next to the AppMark glyph and accent maps. The next app adds one entry there. There is no registry or backend change.
- **Nav icons** reach the shell through an optional `icon` on `NavItem` in `@suite/shell-sdk`. This is an additive field, so the SDK major does not change.
- **Status tones follow the code where it and the design system differ.** `EXPIRED` and `SUSPENDED` stay `danger`, as `status-badge.tsx` has them; the design system's StatusBadge table (EXPIRED neutral, SUSPENDED warning) is corrected at its next update. Values the code lacks (`UNCOLLECTIBLE`, `RETRYING`, `WAITING`, `COMPLETED`, `ENDED`) are added from the design system.

## Phases

| # | Phase | Depends on | Effort | Status |
|---|---|---|---|---|
| 1 | [Sync v4 tokens and theme](./phase-01-sync-tokens-and-theme.md) | none | 1d | Done |
| 2 | [Bug fixes and the i18n key test](./phase-02-bug-fixes-and-i18n-test.md) | 1 | 1d | Done |
| 3 | [UI primitives](./phase-03-ui-primitives.md) | 1 | 2d | Done |
| 4 | [DataTable card list and list kit](./phase-04-data-table-and-list-kit.md) | 3 | 2d | Done |
| 5 | [Sheet and Dialog as bottom sheets](./phase-05-sheet-and-dialog.md) | 3 | 2d | Done |
| 6 | [Shell frame: accent, density, nav, phone and tablet](./phase-06-shell-frame.md) | 3 | 2d | Done |
| 7 | [Launcher home](./phase-07-launcher-home.md) | 6 | 0.5d | Done |
| 8 | [Platform screens](./phase-08-platform-screens.md) | 2, 4, 5, 6 | 1.5d | Done |
| 9 | [Billing screens](./phase-09-billing-screens.md) | 2, 4, 5, 6 | 2d | Done |
| 10 | [Screenshot and accessibility suite, docs](./phase-10-e2e-suite-and-docs.md) | 7, 8, 9 | 1.5d | Review |
| 11 | [Motion, real sizes and the screen audit](./phase-11-motion-and-polish.md) | 10 | 1d | Done |

Phases 3 to 6 touch different files, but run them one after another: an under-inferring executor merging parallel branches is a risk this plan does not take.

## How to work (read before every phase)

1. Work from `frontend/` unless a step says otherwise. Package names: `@suite/ui`, `@suite/remote-kit`, `@suite/shell-core`, `@suite/shell-sdk`, `@suite/remote-platform`, `@suite/remote-billing`, `@suite/platform-shell`, `@suite/tenant-shell`, `@suite/e2e`.
2. Before a task, open the files it names and read them. Do not edit a file the phase does not list. If a needed change is in an unlisted file, follow the Failure Protocol.
3. Colours, sizes and radii come only from tokens (acceptance T1). Never type a hex, `oklch()` or Tailwind palette class (`bg-violet-600`).
4. Keep every existing test passing. Do not delete or weaken an assertion to make a check pass; only the tasks that say so change an existing test.
5. After each phase, run that phase's "Phase gate" commands. Commit with a conventional message (`feat(ui): …`, `fix(shell): …`), no plan or phase ids in the message, and end it with the attribution lines the session asks for.
6. Long-running processes (dev servers, the compose stack): check `lsof -i :<port>` before starting one, reuse what runs, and stop what you started when the phase ends.
7. Every phase file ends with a **Failure Protocol**. A failed check means STOP, not improvise.

## Success metrics

- `pnpm lint && pnpm typecheck && pnpm test` exit 0 in `frontend/`.
- `make e2e` passes with the new `10-visual.spec.ts` and `11-mobile.spec.ts`.
- The screenshot suite writes one PNG for each screen in each of the 12 combinations into `frontend/e2e/test-results/visual/`, and they match `reference/targets/` in structure (manual review once, at the end of phase 10).
- `grep -rnE "#[0-9a-fA-F]{3,8}\b|oklch\(|(bg|text|border)-(zinc|violet|blue|red|green|amber|slate|gray)-[0-9]" frontend/packages frontend/remotes frontend/apps --include=*.tsx --include=*.ts` prints nothing outside tests.

## Risks

| Risk | Mitigation |
|---|---|
| Custom properties resolve on `:root`, so setting `--app-accent` alone does nothing (porting.md rule 2). | Phase 1 task 1.4 writes the full re-declaration block and a test that reads `--primary` under `[data-app]`. |
| Drag-to-close fights the scroll of a long sheet on iOS. | Drag only from the handle and the head (`touch-action: none` there), never the body; phase 5 has a WebKit check. |
| 18 column sets must gain a `mobile` role; one forgotten column would render a card with an empty line. | The field becomes required at the end of phase 9, so typecheck lists any column left without one. |
| e2e spec 08 reads tokens from the v3 snapshot and selects rows. | Phase 1 updates the snapshot and alias handling; phase 10 replaces the selection checks. |

## Unresolved questions

None blocking. Phase 10 asks the owner for one manual review of the screenshots against `reference/targets/`.

Follow-up outside this plan: correct the StatusBadge tone table in the design-system artifact (EXPIRED and SUSPENDED to `danger`) at its next update.

## Validation Log

### Session 1: 2026-11-20

**Verification results**
- Tier: Full (10 phases).
- Claims checked: 76 file paths cited in the phases, plus 14 symbols the steps rely on: `currentPlace`, `Tooltip`, `listQuery`, `useApi`, `IdValue`, `Timestamp`, `asListQuery`, `ShellMessageKey`, `invoicesOptions`, `listRemotesOptions`, `applyTheme`, `useNamespace`, `permissions.billing.invoices`, `AppCode`.
- Verified: 90 | Failed: 0 | Unverified: 0.
- One vague step was made exact: task 8.6 now names `listRemotesOptions`.

**Questions asked: 4**

| Question | Answer | Effect |
|---|---|---|
| Remove row selection from lists with no real bulk action? | Yes, remove everywhere | As planned: tasks 4.4, 10.2 and 10.4 |
| Full 264-screenshot matrix, dark included? | Yes, full | As planned: task 10.2 |
| Status tones where the code and the design system differ? | Keep the code | Recorded under "Decisions"; task 3.2 already keeps the existing map |
| Where launcher content lives? | Static catalogue in the shell | As planned: task 7.1 |

**Whole-plan consistency sweep**
- `plan.md` decisions, phase steps and acceptance references agree. Selection rule: tasks 4.4, 10.2 and 10.4 agree. `data-app` and `data-density` live on `<html>` in phases 6 and 10 and the docs task. The 264 count holds: 22 screens × 12 combinations in task 10.2.
- `mobile` is optional in phase 4 and required in task 9.7. Phase 8 fills Platform, phase 9 fills Billing, and task 9.7 makes the compiler enforce it.
- The `cardOnly` column flag appears in task 4.1 (type), task 4.3 (test) and the phase 8 and 9 tables.
- No unresolved contradiction remains.

<!-- slug: console-ds-v4 -->

### Session 2: 2026-11-20

**What ran**

- Phases 3 to 10 implemented on `dev`, one commit per phase: `feat(ui)` ×3, `feat(shell)` ×2, `feat(platform)`, `feat(billing)`,
  `test(e2e)`.
- `pnpm lint && pnpm typecheck && pnpm test` in `frontend/`: 12 packages, all packages pass, all unit tests pass.
- Billing tests pass under `TZ=UTC` and `TZ=Asia/Saigon`.
- Phase greps: no `IdValue` outside a sheet, no `variant="destructive"` in a sheet, no `datetime-local` in the remotes,
  `mobile: MobileRole` appears once, no colour literal outside tests.
- Browser measurement (task 6.5): aside 56px at 900, `x` below 0 at 390, nav button 44px, top bar 56px.
- `make console` published both shells and both remotes; `make e2e` passed 12/12, including 264 screenshots, the
  contrast and target-size axe checks, and the phone flows.

**Deviations recorded**

- `@suite/ui`'s Tooltip now renders its own Radix provider: the app had none, so the tablet rail's tooltips crashed the
  frame. Same tooltip API; one component file.
- The document's root font is 14px, so Tailwind rem utilities are 12.5% smaller than the plan's px. The measured sizes
  (rail 56, drawer 300, phone bar 56, touch targets 44, avatar 28) use tokens or px instead.
- `useViewCounts` accepts `(params) => unknown` and casts once internally: generated query options cannot be assigned to
  the plan's `UseQueryOptions` signature.
- Radix 1.2 defers the outside-close to the click and gives outside events to the top modal layer only, so the
  RecordSheet guard is proven in two cases rather than one.
- `07-language.spec.ts` also used row selection; it now asserts the translated sort control. `08` reads the invoice
  number as text because the sheet's title is now the amount.

**Open**

- Task 10.7: the owner reviews the four screenshots against `reference/targets/`. Phase 10 and the phase gate stay open
  until that answer is recorded.
