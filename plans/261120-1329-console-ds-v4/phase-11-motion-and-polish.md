---
phase: 11
title: "Motion, real sizes and the screen audit"
status: done
depends_on: [10]
---

# Phase 11: Motion, real sizes and the screen audit

## Why

The owner's review of phase 10 found the console improved but not finished: no animation, and controls that look and
feel smaller than the design says. `plans/reports/review-261121-0803-console-ds-v4-screen-audit.md` has the evidence: a
new audit spec found 1,190 findings. The acceptance file had no motion criterion, axe checks 24px and not 44px, and the
screenshot suite never opened a sheet, dialog or popover.

## Causes and fixes

| Cause | Fix |
|---|---|
| The root font is 14px, so Tailwind's rem steps are 3.5px: `h-11` is 38.5px and no touch target is 44px | `--spacing: 4px` in `theme.css`; heights come from the control tokens; the phone `--size-control-sm` is the touch target |
| `animate-in` is not generated and the design system's keyframes were never ported | keyframes and `animate-*` utilities in `theme.css`, `motion-colors` for hover and focus, enter and leave on sheet, dialog, menu, popover, tooltip, palette, toast, drawer scrim, bulk and unsaved bars and the floating action |
| A dragged sheet unmounted where it was let go | the hook carries it off screen, fades the scrim with the finger, then closes |
| `figure` missing from `TYPE_SCALE`, so tailwind-merge dropped `text-figure` | added; a test compares `TYPE_SCALE` with the sizes in `theme.css` |
| The app row and the open screen were both highlighted | the app row is a heading, only the open screen is highlighted |
| The floating action covered Previous and Next; the closed drawer's shadow bled in | bottom padding on a phone with an action; the shadow belongs to the open drawer; the closed drawer is `inert` |
| The tablet's toggle did nothing | hidden where the rail is forced |
| `Money` drew the code like the amount | the code is its own, quieter element |
| An empty list drew a header over nothing | the empty state fills the frame |
| Phone inputs were 13px | 16px on phones |

## Verification

- `pnpm lint && pnpm typecheck && pnpm test` in `frontend/`: all 12 packages pass.
- `make console`, then `pnpm test` in `frontend/e2e`: 19 of 19 pass, and `12-screen-audit.spec.ts` reports 0 findings (it was
  1,190). The audit is a gate by default; `AUDIT_STRICT=0` only collects.
- `07`, `08`, `10` and `11` were adapted where a test read an overlay or a scan before its enter or leave animation had
  finished; no assertion was removed.

## Out of scope

Seeding plans, coupons, subscriptions and payments so their record sheets are audited; a per-screen comparison against
`reference/targets/`.
