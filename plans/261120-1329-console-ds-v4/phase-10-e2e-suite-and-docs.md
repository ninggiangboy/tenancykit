---
phase: 10
title: "Screenshot and accessibility suite, docs"
status: in review
priority: P1
effort: "1.5d"
dependencies: [7, 8, 9]
---

# Phase 10: Screenshot and accessibility suite, docs

## Goal
A Playwright suite proves v4 on the deployed console:
- every Platform and Billing screen and both homes, in 12 combinations (1280, 900 and 390 wide; light and dark; comfortable and compact);
- no console or HTTP error, and no horizontal scroll on phones;
- no axe contrast or target-size violation;
- the phone flows work (drawer, card list, bottom sheet drag, floating action);
- the older specs match v4.

`docs/console.md` describes v4.

## Read first
- `reference/design-system-v4/acceptance.md`, section 8 "How to verify", and every check id the tests below cite.
- `frontend/e2e/support/{fixtures,console,health,stack}.ts` and `frontend/e2e/tests/04-screens.spec.ts`: reuse their sign-in, fixture and health helpers. Do not write new sign-in code.

## Files to create / modify
- Create: `frontend/e2e/support/visual.ts`
- Create: `frontend/e2e/tests/10-visual.spec.ts`, `frontend/e2e/tests/11-mobile.spec.ts`
- Modify: `frontend/e2e/tests/04-screens.spec.ts` (the entitlement assertion only), `frontend/e2e/tests/08-design-system.spec.ts` (the token and selection assertions only)
- Modify: `docs/console.md` (the design-system section and the e2e list)

## How to run the stack
From the repository root:
1. Check what already runs: `make ps`.
2. Run `make up` only if Caddy and its dependencies are not running.
3. Then `make console` builds and publishes the shells and remotes.
4. `make e2e` runs the suite. To run one spec: `cd frontend/e2e && pnpm test tests/10-visual.spec.ts`.

Leave the stack as you found it. If you started it, stop it with `make down` at the end of the phase.

## Tasks

### Task 10.1: Visual helpers
- Goal: one helper sets the viewport, theme and density, captures a screen and checks it.
- Target files: create `frontend/e2e/support/visual.ts`.
- Steps:
  1. Export the combinations:
     - `VIEWPORTS = { desktop: { width: 1280, height: 800 }, tablet: { width: 900, height: 800 }, phone: { width: 390, height: 844 } }`;
     - `THEMES = ['light', 'dark'] as const`;
     - `DENSITIES = ['comfortable', 'compact'] as const`.
  2. `async function applyLook(page, theme, density)`: run `localStorage.setItem('suite.theme', theme)` and `localStorage.setItem('suite.density', density)` through `page.evaluate`, then `page.reload()` and `waitForShell(page)`.
  3. `async function capture(page, name)` does three things:
     - takes a full-page screenshot to `test-results/visual/${name}.png`;
     - asserts `await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)` is `true` when the viewport width is 390 (M1);
     - asserts `page.getByText('That did not work')` has count 0.
  4. `async function axeLook(page)` runs `new AxeBuilder({ page }).withRules(['color-contrast', 'target-size']).analyze()` and returns the violation ids with their first target selector.
- Verify: `pnpm --filter @suite/e2e typecheck` exits 0.

### Task 10.2: The screenshot suite
- Goal: 22 screens times 12 combinations, captured and checked.
- Target files: create `frontend/e2e/tests/10-visual.spec.ts`.
- Steps:
  1. Copy the screen lists `PLATFORM_PAGES` and `BILLING_GROUPS` from `04-screens.spec.ts`. Add the Billing screens 04 does not list: payments, refunds, subscriptions, coupons, redemptions, reservations and webhooks, by their route ids in `remotes/billing/src/routes.tsx`. Add Home for each shell.
  2. Provision the fixture exactly as 04 does: `provisionTenantFixture`, `waitForBilling`, `seedBilling`. Sign in once per shell. Watch health with `watchConsole`, allowing only the Stripe configuration 404, copying the `allowStatus` from 04.
  3. For each viewport, `page.setViewportSize(...)`. For each theme and density, `applyLook`. Then for each screen: `openRemote`, wait for the level-1 heading, then `capture(page, \`${shell}-${screen}-${viewport}-${theme}-${density}\`)`.
  4. On each remote screen, assert `await page.evaluate(() => document.documentElement.dataset.app)` equals the remote's app code (T3); on Home, `undefined`.
  5. Run `axeLook` for comfortable density at desktop and phone, in both themes, and expect `[]` (T6, M2).
  6. On the invoices screen at desktop, assert three things:
     - in comfortable density a body row's `getBoundingClientRect().height` is 52, and in compact 40 (L1);
     - `page.locator('table input[type="checkbox"]')` has count 0 (L5);
     - the computed `background-color` of the "Create invoice" button equals the browser's resolution of `var(--app-billing)` (T4; use the `resolvedColor` helper pattern from 08).
  7. Set `test.setTimeout(1_800_000)`.
- Success criteria: 264 PNG files exist and the test passes.
- Verify: `cd frontend/e2e && pnpm test tests/10-visual.spec.ts` exits 0, then `ls test-results/visual | wc -l` prints `264`.

### Task 10.3: Phone flows
- Goal: the phone layout works by touch, not only by looks (M3, M5, M6, M7).
- Target files: create `frontend/e2e/tests/11-mobile.spec.ts`.
- Steps:
  1. Viewport 390×844 with `hasTouch: true` (`test.use({ viewport: { width: 390, height: 844 }, hasTouch: true })`). Use the tenant fixture with seeded billing, as 04 does.
  2. Drawer:
     - click the button named "Open navigation", then the visible "Invoices" link;
     - expect the heading "Invoices" and the `aside` to be off screen again (`boundingBox().x < 0`).
  3. Card list: expect `page.getByRole('list', { name: 'Invoices' }).getByRole('listitem')` to contain the seeded invoice number. Tap it: a `dialog` appears whose `boundingBox()` bottom equals 844 (±2) and whose height is at least 700.
  4. Drag to close: find the grab handle as the first child of the dialog with the class `touch-none`. Move the mouse to its centre, `mouse.down()`, `mouse.move(x, y + 400, { steps: 10 })`, `mouse.up()`. Expect the dialog to be hidden.
  5. Back: tap the card again, then `page.goBack()`; expect the dialog hidden and the URL without `open=`.
  6. Floating action: the button "Create invoice" is visible and its `boundingBox()` bottom is within 40px of 844. Tap it: a dialog appears at the bottom (bottom equals 844 ±2) with a full-width primary button (its width at least 300).
  7. Density: open the account menu, tap "Compact", and expect `document.documentElement.dataset.density` to be `compact`.
  8. `health.expectClean()` at the end, with the Stripe 404 allowed.
- Verify: `cd frontend/e2e && pnpm test tests/11-mobile.spec.ts` exits 0.

### Task 10.4: Bring the older specs to v4
- Target files: `frontend/e2e/tests/04-screens.spec.ts` (line 73), `frontend/e2e/tests/08-design-system.spec.ts`.
- Steps:
  1. In 04, replace `await expect(sheet.locator('li').filter({ hasText: 'Billing' })).toContainText('On')` with `await expect(sheet.locator('li').filter({ hasText: 'Billing' }).getByRole('switch')).toBeChecked()`.
  2. In 08, on the invoice list (Billing):
     - compare `primary` with `app-billing` instead of `primary`, in both themes (`tokenMatches(page, 'primary', theme)` becomes a comparison of `var(--primary)` with the resolution of the `app-billing` token);
     - replace `await selectActiveRow(page)` with `await expect(page.locator('table input[type="checkbox"]')).toHaveCount(0)`.
  3. In 08's role-page test, replace `await selectActiveRow(page)` with the same no-checkbox assertion. Keep every other assertion.
- Verify: `cd frontend/e2e && pnpm test tests/04-screens.spec.ts tests/08-design-system.spec.ts` exits 0.

### Task 10.5: The whole suite
- Verify:
  - `make e2e`, from the repository root, exits 0;
  - the colour-literal check from `plan.md` "Success metrics" prints nothing.

### Task 10.6: Docs
- Goal: `docs/console.md` describes v4.
- Target files: `docs/console.md`, the "The design system" section and the end-to-end test list.
- Steps:
  1. Set the version to the artifact version `1795165481-3dca` (tokens `version` 4). State that the snapshot lives in `plans/261117-1456-suite-console/reports/design-system/` and the full v4 copy in `plans/261120-1329-console-ds-v4/reference/design-system-v4/`.
  2. In the list of rules, replace "monochrome first" with "one accent per app". Add "two densities" and "phone and tablet layouts".
  3. Add one paragraph: the shell sets `data-app` and `data-density` on `<html>`, because Radix portals sheets and dialogs into `<body>`; the app-scope block in `packages/ui/src/styles/theme.css` re-declares the aliases (`--primary` and the others) because custom properties resolve where they are declared.
  4. In the e2e section, add `10-visual.spec.ts` (screens × 12 combinations, axe contrast and target size) and `11-mobile.spec.ts` (drawer, card list, bottom sheet, floating action, density).
- Verify: `grep -c "one accent per app\|data-density\|10-visual" docs/console.md`, from the repository root, prints `3` or more.

### Task 10.7: Owner review
- Goal: the owner confirms the result looks like the design.
- Steps:
  1. Run `open frontend/e2e/test-results/visual` (macOS) and report to the owner these paths, one per combination they should look at first:
     - `tenant-invoices-desktop-light-comfortable.png`;
     - `tenant-invoices-phone-dark-comfortable.png`;
     - `platform-tenants-tablet-light-compact.png`;
     - `tenant-home-desktop-light-comfortable.png`.
  2. Ask the owner to compare them with `plans/261120-1329-console-ds-v4/reference/targets/`.
  3. STOP and wait for the answer. Do not mark the plan complete until the owner approves.
- Verify: the owner's approval is recorded in this file under a heading "Owner review" with the date.

## Owner review

**2026-11-20 — awaiting the owner's answer.** The four screenshots the plan names are ready:

- `frontend/e2e/test-results/visual/tenant-invoices-desktop-light-comfortable.png`
- `frontend/e2e/test-results/visual/tenant-invoices-phone-dark-comfortable.png`
- `frontend/e2e/test-results/visual/platform-tenants-tablet-light-compact.png`
- `frontend/e2e/test-results/visual/tenant-home-desktop-light-comfortable.png`

Ask the owner to compare them with `reference/targets/`. Task 10.7 and the phase gate stay open until
the answer is recorded here.

## Todo
- [x] Task 10.1: Visual helpers
- [x] Task 10.2: The screenshot suite
- [x] Task 10.3: Phone flows
- [x] Task 10.4: Bring the older specs to v4
- [x] Task 10.5: The whole suite
- [x] Task 10.6: Docs
- [ ] Task 10.7: Owner review
- [ ] Phase gate passed

## Phase gate
- `pnpm lint && pnpm typecheck && pnpm test` in `frontend/` exits 0.
- `make e2e` exits 0.
- The owner approved the screenshots (task 10.7).

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
