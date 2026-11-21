---
title: Suite Console design system v4 and its implementation plan
date: 2026-11-20
summary: "The UI review led to design system v4 (app accents, density, phone layouts) and a 10-phase handover plan"
---

# Suite Console design system v4 and its implementation plan

## What happened
The 2026-11-20 screenshots of the console looked unfinished. Most of the cause was the v3 design system itself rather than the code: it was "monochrome first", it told apps apart by glyph only, and it showed status as a 6px dot. On top of that sat code drift and a few visible bugs:
- the raw key `shell.open` on Home;
- the breadcrumb "Platform › Platform";
- "Nothing changed" on a failed read;
- a checkbox column on every list, there only to copy IDs.

## Decision
The owner confirmed v4 in `/ak-advise`, and it was published to the design-system artifact as version 1795165481-3dca. It brings:
- one accent per app;
- status as a tinted pill with an icon;
- a comfortable/compact switch;
- card lists, bottom sheets and a nav drawer on phones, and an icon rail on tablets;
- Home as a launcher;
- an acceptance-criteria section and a porting section.

Every app colour pair was checked at 4.9:1 contrast or better in both themes. All 35 previews render without errors.

The plan is `plans/261120-1329-console-ds-v4/`. It has 10 phases written in the handover format, so a weaker executor can follow them, and a full copy of the design system plus target screenshots in `reference/`. Validation checked 90 claims and none failed. Four decisions were confirmed:
- no row selection without a real bulk action;
- the full 264-screenshot matrix;
- status tones follow the code;
- launcher content comes from a static catalogue in the shell.

## Lessons
- `tokens.css` resolves aliases on `:root`. Setting `--app-accent` on an element does not change `--primary` below it, so the app-scope block must declare `--primary` and the other aliases again.
- Radix portals sheets and dialogs into `<body>`, so `data-app` and `data-density` belong on `<html>`.
- vaul is unmaintained (its last release was in December 2024). Drag-to-close will be a small hook on Radix Dialog.
- `@suite/ui` controls use fixed `h-7`/`h-8` classes, so density only works after they switch to the `--size-*` variables.

## Next steps
Run `/ak-cook plans/261120-1329-console-ds-v4/plan.md --advice` in a fresh session. Correct the StatusBadge tone table in the design system (EXPIRED and SUSPENDED become danger) at its next update.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
