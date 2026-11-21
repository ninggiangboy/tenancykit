---
phase: 3
title: "UI primitives"
status: done
priority: P1
effort: "2d"
dependencies: [1]
---

# Phase 3: UI primitives

## Goal
The small components in `@suite/ui` look and behave as v4 says: tinted status pills with icons, coloured app marks, accent buttons with a `danger` variant, heights that follow density and phones, saved-view tiles, always-visible copy icons on touch screens, a `useMedia` hook and a UTC date-time field.

## Read first
- `reference/design-system-v4/components/StatusBadge/README.md`, `AppMark/README.md`, `Button/README.md`, `Tabs/README.md`, `Field/README.md`, `Switch/README.md`.
- `reference/design-system-v4/components/bundle.css`, the sections `/* Button */`, `/* Status badge… */`, `/* AppMark… */` and `/* Tabs */`: the exact sizes and colours to port.
- `reference/targets/StatusBadge-light-700.png`, `Button-light-1000.png`, `AppMark-light-1000.png`, `Tabs-light-1000.png`.

## Files to create / modify
- Create: `frontend/packages/ui/src/use-media.ts`, `frontend/packages/ui/src/use-media.test.ts`, `frontend/packages/ui/src/utc-datetime.ts`, `frontend/packages/ui/src/utc-datetime.test.ts`
- Modify: `frontend/packages/ui/src/status-badge.tsx` and `status-badge.test.tsx`
- Modify: `frontend/packages/ui/src/app-mark.tsx`
- Modify: `frontend/packages/ui/src/button.tsx`
- Modify: `frontend/packages/ui/src/field.tsx`, `frontend/packages/ui/src/combobox.tsx` (heights only)
- Modify: `frontend/packages/ui/src/tabs.tsx`
- Modify: `frontend/packages/ui/src/copy-id.tsx`
- Modify: `frontend/packages/ui/src/index.ts` (exports)
- Modify: `frontend/packages/remote-kit/src/forms/form-fields.tsx` (accept `type="datetime-utc"`)

## Tasks

### Task 3.1: `useMedia` hook
- Goal: one hook tells the kit and the shell whether a media query matches. Screens never call it.
- Target files: create `frontend/packages/ui/src/use-media.ts`; export it from `index.ts`.
- Steps:
  1. Export `PHONE_QUERY = '(max-width: 767.98px)'` and `TABLET_QUERY = '(min-width: 768px) and (max-width: 1023.98px)'`.
  2. Export `function useMedia(query: string): boolean`. Use `useSyncExternalStore`: subscribe with `matchMedia(query).addEventListener('change', …)`, read with `matchMedia(query).matches`, and return `false` when `globalThis.matchMedia` is undefined.
  3. Write `use-media.test.ts`: stub `window.matchMedia` with an object whose `matches` you can flip and whose listeners you can call; assert that the hook returns the new value after a change event.
- Success criteria: the hook updates on change.
- Verify: `pnpm --filter @suite/ui test -- use-media` exits 0.

### Task 3.2: StatusBadge as a tinted pill with an icon
- Goal: badges look like `reference/targets/StatusBadge-light-700.png` (acceptance S1, S2).
- Target files: `frontend/packages/ui/src/status-badge.tsx` (`tones`, `toneClass`, `StatusBadge`); `status-badge.test.tsx`.
- Steps:
  1. Add the missing values to `tones`: `UNCOLLECTIBLE: 'danger'`, `RETRYING: 'warning'`, `WAITING: 'warning'`, `COMPLETED: 'success'`, `ENDED: 'neutral'`. Do not change the tones already in the map.
  2. Replace `toneClass` with two maps:
     - pill: `neutral: 'bg-neutral-soft text-muted-foreground'`, `info: 'bg-info-soft text-info'`, `success: 'bg-success-soft text-success'`, `warning: 'bg-warning-soft text-warning'`, `danger: 'bg-destructive-soft text-destructive'`;
     - icon: `info: 'clock'`, `success: 'check'`, `warning: 'alert'`, `danger: 'x-circle'`, neutral none. Use names that exist in `IconName` in `icon.tsx`; if `alert` or `x-circle` is missing there, add the Lucide icons `TriangleAlert` and `CircleX` to that map.
  3. The element: `inline-flex h-[22px] items-center gap-1 rounded-md px-2 text-caption font-semibold`, plus the pill class. Inside: the icon (`<Icon name=… size={12} strokeWidth={2.5} aria-hidden />`) when the tone has one, then the word. Keep `data-tone`. Remove the dot.
  4. `variant="plain"`: no pill and no icon; classes `text-small text-muted-foreground`. Remove the `outline` variant. If any caller passes `variant="outline"`, `pnpm typecheck` will list it; delete that prop at each place.
  5. Update `status-badge.test.tsx`: assert the tone class (`bg-success-soft` for `PAID`) and the presence of an `svg` for non-neutral tones instead of the dot.
- Success criteria: every status renders an icon (except neutral) and a word in a tinted pill.
- Verify: `pnpm --filter @suite/ui test -- status-badge` exits 0, and `pnpm typecheck` exits 0.

### Task 3.3: AppMark in the app's colour
- Goal: the tile is the app's accent with its glyph in white (light) or ink (dark).
- Target files: `frontend/packages/ui/src/app-mark.tsx` (`sizes`, `AppMarkProps`, `AppMark`).
- Steps:
  1. Add `tone?: 'color' | 'neutral'` to `AppMarkProps` (default `color`).
  2. On the tile `span`, set `data-app={app}` when `app` is a known `AppCode`. With `tone` `color`, use the classes `bg-app-accent text-app-accent-foreground`. With `neutral`, keep today's classes.
  3. Sizes: `sm` 20px (radius 6px, icon 12), `md` 26px (radius 7px, icon 14), `lg` 40px (`rounded-lg`, icon 18).
  4. Update the doc comment: "The app's glyph on its accent colour; `tone: 'neutral'` where many marks would compete."
- Success criteria: `<AppMark app="billing" />` renders a violet tile in light theme.
- Verify: `pnpm --filter @suite/ui test` exits 0. If a test asserted the neutral classes, update it to expect `bg-app-accent` and `data-app="billing"`.

### Task 3.4: Button heights, `danger` variant, accent link
- Goal: buttons follow density and phones, and destructive page actions are red text (S4).
- Target files: `frontend/packages/ui/src/button.tsx` (the `button` cva).
- Steps:
  1. Sizes: `sm: 'h-(--size-control-sm) px-3 text-small'`, `md: 'h-(--size-control-md) px-3.5 text-body-strong'`, `lg: 'h-(--size-control-lg) px-5 text-body-strong'`. Icon-only compound widths: `w-(--size-control-sm)`, `w-(--size-control-md)`, `w-(--size-control-lg)`.
  2. Add the variant `danger: 'text-destructive hover:bg-destructive-soft'`.
  3. Change `link` to `'text-link underline-offset-4 hover:underline'`.
  4. Base class: change `rounded-md` only if the radius token did not already give 8px; `rounded-md` reads `--radius-md`, which is now 8px, so no change is needed.
  5. Add `danger` to the `ButtonProps['variant']` type if it is declared by hand.
- Success criteria: in comfortable density a default button is 36px tall, in compact 32px, on a phone 44px.
- Verify: `pnpm --filter @suite/ui test` and `pnpm --filter @suite/ui typecheck` exit 0.

### Task 3.5: Field and Combobox heights
- Goal: inputs follow density, and are 16px text on phones so iOS does not zoom (M8).
- Target files: `frontend/packages/ui/src/field.tsx` (line 53, the size classes); `frontend/packages/ui/src/combobox.tsx` (the trigger `h-8` near line 97 and the search input `h-7` near line 127).
- Steps:
  1. In `field.tsx`, replace `'h-7 px-2'` with `'h-(--size-control-sm) px-2.5'` and `'h-8 px-2.5'` with `'h-(--size-control-md) px-3'`. Add `max-md:text-base` to the input's class.
  2. In `combobox.tsx`, replace the trigger's `h-8` with `h-(--size-control-md)` and add `max-md:text-base`. Replace the search input's `h-7` with `h-(--size-control-sm)`.
  3. In `field.tsx`, add `'datetime-utc'` to the `type` union. For it, render `<input type="text" inputMode="numeric" placeholder="YYYY-MM-DD HH:mm" pattern="\d{4}-\d{2}-\d{2} \d{2}:\d{2}" autoComplete="off">` with the same classes as text inputs. Keep `'datetime-local'` in the union for now, and add a comment above the union: `// datetime-local is deprecated: it shows the viewer's local time; use datetime-utc.` Phase 9 moves the six screen fields over and removes `datetime-local`.
- Success criteria: inputs are 36px tall by default.
- Verify: `pnpm --filter @suite/ui test` exits 0 and `pnpm typecheck` exits 0.

### Task 3.6: UTC date-time helpers
- Goal: one place converts between `YYYY-MM-DD HH:mm` (UTC) and ISO 8601.
- Target files: create `frontend/packages/ui/src/utc-datetime.ts` and `utc-datetime.test.ts`; export from `index.ts`; `frontend/packages/remote-kit/src/forms/form-fields.tsx`.
- Steps:
  1. `formatUtcInput(date: Date): string` returns `date.toISOString().slice(0, 16).replace('T', ' ')`.
  2. `parseUtcInput(value: string): string | null` returns `null` unless `value.trim()` matches `/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/` and makes a valid date; otherwise it returns `new Date(\`${value.trim().replace(' ', 'T')}:00Z\`).toISOString()`.
  3. Tests:
     - `formatUtcInput(new Date('2026-11-20T09:05:00Z'))` returns `'2026-11-20 09:05'`;
     - `parseUtcInput('2026-11-20 09:05')` returns `'2026-11-20T09:05:00.000Z'`;
     - `parseUtcInput('20/11/2026')` returns `null`;
     - `parseUtcInput('2026-13-40 10:00')` returns `null`.
  4. In `form-fields.tsx`, let `FormField`'s `type` accept `'datetime-utc'` and pass it through to `Field`.
- Success criteria: the helpers are exact and timezone independent.
- Verify: `TZ=Asia/Saigon pnpm --filter @suite/ui test -- utc-datetime` and `TZ=America/New_York pnpm --filter @suite/ui test -- utc-datetime` both exit 0.

### Task 3.7: Tabs `tiles` variant
- Goal: saved views show as tiles with counts on wide screens and as pills on phones (L4), as in `reference/targets/Tabs-light-1000.png`.
- Target files: `frontend/packages/ui/src/tabs.tsx`.
- Steps:
  1. Add `'tiles'` to `variant`. Items already take `count?`; format a number count with `toLocaleString('en-US')`.
  2. Tiles from `md` up: the list is `grid w-full gap-3 [grid-template-columns:repeat(auto-fit,minmax(140px,1fr))]`. Each trigger is `flex flex-col items-start gap-0.5 rounded-lg bg-card px-3.5 py-3 text-small text-muted-foreground shadow-[0_0_0_1px_var(--border)]`. The count is `text-figure text-foreground tabular-nums`. The selected trigger adds `text-primary shadow-[0_0_0_2px_var(--primary)]`.
  3. Below `md`: the list is `flex gap-2 overflow-x-auto`, triggers are pills (`h-9 flex-row items-center gap-1.5 rounded-full px-3.5`), the count is `text-small font-semibold`, and the selected pill is `bg-app-accent-soft text-primary` with no ring. Use Tailwind `max-md:` variants on the same elements, not `useMedia`.
  4. Keep the Radix Tabs semantics (role `tab`, arrow keys).
- Success criteria: the e2e locator `getByRole('tab', { name: 'Paid' })` still finds the tab. Its accessible name starts with the label; the count may follow.
- Verify: `pnpm --filter @suite/ui test -- tabs` exits 0. If no test exists for `tiles`, add one asserting that the count renders and the selected tab has `aria-selected="true"`.

### Task 3.8: CopyId on touch screens
- Goal: the copy icon is always visible where there is no hover.
- Target files: `frontend/packages/ui/src/copy-id.tsx`.
- Steps:
  1. On the icon element that is hidden until hover, add the classes `[@media(hover:none)]:w-3.5 [@media(hover:none)]:opacity-100 [@media(hover:none)]:ml-0`. Match the hidden-state classes it already has.
- Success criteria: on a touch device the copy icon shows without hover.
- Verify: `pnpm --filter @suite/ui test` exits 0.

## Todo
- [x] Task 3.1: `useMedia` hook
- [x] Task 3.2: StatusBadge as a tinted pill with an icon
- [x] Task 3.3: AppMark in the app's colour
- [x] Task 3.4: Button heights, `danger` variant, accent link
- [x] Task 3.5: Field and Combobox heights
- [x] Task 3.6: UTC date-time helpers
- [x] Task 3.7: Tabs `tiles` variant
- [x] Task 3.8: CopyId on touch screens
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
