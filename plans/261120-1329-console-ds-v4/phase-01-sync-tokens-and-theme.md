---
phase: 1
title: "Sync v4 tokens and theme"
status: done
priority: P1
effort: "1d"
dependencies: []
---

# Phase 1: Sync v4 tokens and theme

## Goal
`@suite/ui` generates its CSS variables from the v4 `tokens.json`, the theme declares the app-accent, density and phone blocks, and the design-system snapshot the e2e suite reads is v4.

## Read first
- `reference/design-system-v4/porting.md`: the "Rules that are easy to get wrong" and "Sync procedure" sections.
- `reference/design-system-v4/components/bundle.css`, lines 1 to 30: the `[data-app]`, density and phone blocks you will port.
- `frontend/packages/ui/scripts/build-tokens.mjs`, `frontend/packages/ui/src/styles/theme.css`, `frontend/packages/ui/src/tokens.test.ts`.

## Files to create / modify
- Modify: `frontend/packages/ui/design/tokens.json` (replace with v4)
- Modify: `frontend/packages/ui/scripts/build-tokens.mjs`
- Regenerate: `frontend/packages/ui/src/styles/tokens.css` (never by hand)
- Modify: `frontend/packages/ui/src/styles/theme.css`
- Modify: `frontend/packages/ui/src/tokens.test.ts`
- Create: `frontend/packages/ui/src/app-scope.test.ts`
- Modify: `plans/261117-1456-suite-console/reports/design-system/tokens.json`, `README.md`, `components-index.d.ts` (snapshot)
- Modify: `frontend/e2e/tests/08-design-system.spec.ts` (the `tokenValue` helper only)

## Tasks

### Task 1.1: Copy the v4 tokens
- Goal: the package's `tokens.json` is v4.
- Target files: `frontend/packages/ui/design/tokens.json`.
- Steps:
  1. From the repository root, run `cp plans/261120-1329-console-ds-v4/reference/design-system-v4/tokens.json frontend/packages/ui/design/tokens.json`.
- Success criteria: the file's `"version"` is `4` and it defines `app-billing`.
- Verify: `node -e "const t=require('./frontend/packages/ui/design/tokens.json');console.log(t.version, t.color.tokens.some(x=>x.name==='app-billing'))"` from the repository root prints `4 true`.

### Task 1.2: Teach the build script the new families
- Goal: `tokens.css` also carries spacing and the new size tokens, with the same naming rules as today.
- Target files: `frontend/packages/ui/scripts/build-tokens.mjs`.
- Steps:
  1. Add `const spacing = tokens.spacing.tokens.map((token) => \`  --${token.name.replace('.', '_')}: ${token.value};\`)` after `sizes`. The names are already `space-1`, `space-1_5`, and so on.
  2. Insert `${spacing.join('\n')}` into the `:root` block, after `${sizes.join('\n')}`.
  3. Leave colour alias resolution as it is: aliases such as `{app-accent}` resolve to the literal value of the target in each theme.
  4. Do not emit the `breakpoint` family: Tailwind's `md` (768px) and `lg` (1024px) already equal it.
- Success criteria: the script runs and writes the new variables.
- Verify: `pnpm --filter @suite/ui tokens:build` exits 0. Then `grep -c -- "--size-control-md-compact: 32px;\|--space-8: 32px;\|--app-billing:\|--success-soft:" frontend/packages/ui/src/styles/tokens.css` (from the repository root) prints `6`: the size and spacing lines once, and the two colours in both `:root` and `.dark`.

### Task 1.3: Update the token test to v4 values
- Goal: the token test describes v4.
- Target files: `frontend/packages/ui/src/tokens.test.ts`.
- Steps:
  1. In "writes the radius, shadow, size and timing scales", change `--radius-lg: 8px;` to `--radius-lg: 10px;` and `--size-control-md: 32px;` to `--size-control-md: 36px;`. Add `expect(css).toContain('--size-touch-target: 44px;')` and `expect(css).toContain('--space-8: 32px;')`.
  2. In "keeps the shadcn names the components read", add these names to the list: `app-accent`, `app-accent-soft`, `app-accent-foreground`, `app-platform`, `app-billing`, `success-soft`, `warning-soft`, `destructive-soft`, `info-soft`, `neutral-soft`.
- Success criteria: the test covers v4 names and values.
- Verify: `pnpm --filter @suite/ui test -- tokens` exits 0.

### Task 1.4: App scope, density and phone blocks in the theme
- Goal: an element with `data-app="billing"` makes everything below it use Billing's accent, and `data-density="compact"` shrinks controls and rows.
- Target files: `frontend/packages/ui/src/styles/theme.css`.
- Steps:
  1. After `@import "./tokens.css";`, add this block. It is `bundle.css` lines 7 to 27 renamed to the `--size-*` names that `build-tokens.mjs` writes:
     ```css
     :root { --link: var(--brand); --page-pad: var(--space-8); --page-gap: var(--space-5); }
     [data-app] { --link: var(--app-accent); --primary: var(--app-accent); --primary-foreground: var(--app-accent-foreground); --selection: var(--app-accent-soft); --ring: var(--app-accent); --ring-halo: color-mix(in oklab, var(--app-accent) 28%, transparent); --sidebar-ring: var(--app-accent); }
     [data-app="platform"] { --app-accent: var(--app-platform); --app-accent-soft: var(--app-platform-soft); }
     ```
     Then one `[data-app="<code>"]` line for each of `billing`, `customer`, `mailroom`, `notification`, `survey`, `loyalty`, `booking`, `attribution`, `workflow` and `helpdesk`, in the same shape.
  2. Add the density block:
     ```css
     [data-density="compact"] { --size-control-sm: var(--size-control-sm-compact); --size-control-md: var(--size-control-md-compact); --size-row-height: var(--size-row-height-compact); --page-pad: var(--space-6); --page-gap: var(--space-4); }
     @media (max-width: 767.98px) { :root, [data-density] { --size-control-md: var(--size-touch-target); --size-control-sm: 36px; --page-pad: var(--space-4); --page-gap: var(--space-4); } }
     ```
  3. In `@theme inline`, add `--color-app-accent: var(--app-accent);`, `--color-app-accent-soft: var(--app-accent-soft);`, `--color-app-accent-foreground: var(--app-accent-foreground);`, `--color-success-soft: var(--success-soft);`, `--color-warning-soft: var(--warning-soft);`, `--color-destructive-soft: var(--destructive-soft);`, `--color-info-soft: var(--info-soft);`, `--color-neutral-soft: var(--neutral-soft);` and `--color-link: var(--link);`.
  4. In `@theme`, change `--text-title` to 24px, line-height 30px, weight 700; `--text-metric` to line-height 34px, weight 700. Add `--text-figure: 20px; --text-figure--line-height: 26px; --text-figure--letter-spacing: -0.02em; --text-figure--font-weight: 600;`.
- Success criteria: Tailwind classes `bg-app-accent`, `bg-success-soft`, `text-link` and `text-figure` exist, and the accent follows `data-app`.
- Verify: no verification needed; task 1.5 tests it.

### Task 1.5: Test that the accent follows `data-app`
- Goal: a unit test proves rule 2 of porting.md.
- Target files: create `frontend/packages/ui/src/app-scope.test.ts`.
- Steps:
  1. Read `theme.css` as text with `readFileSync` (the same way `tokens.test.ts` reads `tokens.css`).
  2. Assert that the text contains `[data-app] {` followed by `--primary: var(--app-accent)` and `--selection: var(--app-accent-soft)` in the same rule.
  3. For each code in `['platform','billing','customer','mailroom','notification','survey','loyalty','booking','attribution','workflow','helpdesk']`, assert that the text contains `[data-app="${code}"] { --app-accent: var(--app-${code}); --app-accent-soft: var(--app-${code}-soft); }`.
  4. Assert that it contains `[data-density="compact"]` and `--size-control-md: var(--size-control-md-compact)`.
- Success criteria: the test passes, and fails if any of the eleven app lines is removed.
- Verify: `pnpm --filter @suite/ui test -- app-scope` exits 0. Then delete the `helpdesk` line from `theme.css`, run the same command, and confirm it exits non-zero; restore the line and rerun until it exits 0.

### Task 1.6: Update the design-system snapshot and the e2e token helper
- Goal: the snapshot the e2e suite reads is v4, and its helper resolves aliases.
- Target files: `plans/261117-1456-suite-console/reports/design-system/{tokens.json,README.md,components-index.d.ts}`; `frontend/e2e/tests/08-design-system.spec.ts` (`tokenValue` only).
- Steps:
  1. From the repository root:
     - `cp plans/261120-1329-console-ds-v4/reference/design-system-v4/tokens.json plans/261117-1456-suite-console/reports/design-system/tokens.json`
     - `cp plans/261120-1329-console-ds-v4/reference/design-system-v4/README.md plans/261117-1456-suite-console/reports/design-system/README.md`
     - `cp plans/261120-1329-console-ds-v4/reference/design-system-v4/components/index.d.ts plans/261117-1456-suite-console/reports/design-system/components-index.d.ts`
  2. In `08-design-system.spec.ts`, change `tokenValue` so that a string value of the form `{name}` resolves to that token's value in the same theme, recursively. This is the same logic as `resolveValue` in `frontend/packages/ui/src/tokens.test.ts`. Widen the `tokens` type to `value: string | { light: string; dark: string }`.
  3. Do not change the test's assertions in this phase. `primary` on the invoice list will differ once phase 6 sets `data-app`; phase 10 updates that line.
- Success criteria: the spec still typechecks.
- Verify: `pnpm --filter @suite/e2e typecheck` exits 0.

## Todo
- [x] Task 1.1: Copy the v4 tokens
- [x] Task 1.2: Teach the build script the new families
- [x] Task 1.3: Update the token test to v4 values
- [x] Task 1.4: App scope, density and phone blocks in the theme
- [x] Task 1.5: Test that the accent follows `data-app`
- [x] Task 1.6: Update the design-system snapshot and the e2e token helper
- [x] Phase gate passed

## Phase gate
- `pnpm --filter @suite/ui test` exits 0.
- `pnpm typecheck` exits 0.
- `pnpm lint` exits 0.

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
