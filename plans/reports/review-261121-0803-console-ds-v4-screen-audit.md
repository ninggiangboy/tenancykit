# Console DS v4: screen audit review

Date: 2026-11-21. Plan reviewed: `plans/261120-1329-console-ds-v4/plan.md`.

## Method

A new e2e spec, `frontend/e2e/tests/12-screen-audit.spec.ts` with `frontend/e2e/support/audit.ts`, walks every screen of
Platform and Billing plus both homes, in five looks (desktop light comfortable, desktop dark compact, tablet, phone light
comfortable, phone dark compact). In the two deep looks it also opens each tab, each filter popover, the first row's sheet
and each of that sheet's buttons, and each page action. A separate test forces a slow and a failing response on every list.
It measures what `acceptance.md` can measure, captures 271 images, and writes `findings.json` and `report.md` to
`frontend/e2e/test-results/audit/` (or `AUDIT_DIR`). It fails only with `AUDIT_STRICT=1`.

Run: `cd frontend/e2e && pnpm exec playwright test tests/12-screen-audit.spec.ts`.

Result of the first run: 1,190 findings in 17 rules, 7 of 7 tests passed (non-strict).

## What holds

No console error or failed request on any screen. No sideways scroll at 390px. No raw UUID in a list. No raw message key.
Every screen has one h1. Phone sheets and dialogs dock to the bottom at 88% or less and have a grab handle. The failed-read
state offers Retry and does not say "Nothing changed" on the lists that load on arrival.

## Findings, by weight

| Count | Rule | What | Cause |
|---|---|---|---|
| 489 | L1 | Controls shorter than the density height: top-bar icon buttons 21px, header search 24.5px, facet buttons 24.5px, sort buttons 20px, nav links 18 to 25px | Heights are Tailwind rem classes (`h-7`, `size-8`, `p-1`) and the document root is 14px, so each is 12.5% short and none follows `--size-control-*` |
| 325 | M2 | Phone tap targets below 44px: avatar 28px, dialog Cancel and Create 38.5px, facet checkboxes 14px, pager buttons 36px, launcher shortcut links 25px | `h-11` is 38.5px at a 14px root; the plan's measured 44px covered only the nav button and top bar |
| 64, 46, 44 | motion | No enter animation on sheets and dialogs, no exit animation, no enter animation on popovers and menus | `animate-in` is not generated (no tw-animate-css) and the design system's keyframes were never ported |
| 57 | L6 | Two highlighted nav items (the app row and the open screen) on 19 screens | `appLink` uses the active item style |
| 56 | M8 | Phone inputs below 16px (search 13px) | toolbar search is `text-small` |
| 37 | L1 | Search inputs 24.5px and 21px instead of 32px (28px compact) | `h-7` in `list-toolbar.tsx` |
| 21 | M2 | Dialog close button 21px on phone | `p-1` icon button in `dialog.tsx` |
| 19 | C3 | Currency code not quieter than the amount (invoices, charges, wallets) | `Money` renders one span |
| 12 | M6 | Floating action button covers Previous and Next at the end of the page (Platform tenants, users, roles) | page bottom padding is 32px, the button needs about 80px |
| 6 | tiles | Saved-view count is 12px, not the 20px figure | `figure` is missing from `TYPE_SCALE` in `ui/src/cn.ts`, so tailwind-merge drops `text-figure` |
| 4 | C1 | Home h1 is 24px on phone, not 22px | home does not use the phone title size |
| 1 | M9 | Tablet rail's toggle button does nothing | `rail` is forced on tablets |

Harness notes: five page-action clicks timed out (a "Close" button in the page head of Remotes, and Create user and Create
endpoint on a phone; each needs a look), and the loading and error check on Systems and Redemptions was dropped because those
screens first ask for a tenant or a wallet.

## Coverage limits

The fixture seeds one customer, wallet, charge, invoice, usage event and webhook endpoint. Payments, refunds, subscriptions,
plans, coupons, redemptions and reservations are empty, so their record sheets and card lists were not exercised. Platform
screens have real data.

## Why the plan passed

`acceptance.md` has no motion criterion, the phase files contain no keyframe task, axe `target-size` checks the WCAG minimum of
24px and not 44px, and the screenshot suite never opened a sheet, dialog or popover.

## Resolution

Fixed in phase 11 of the plan: 1,190 findings to 0, the full e2e suite 19 of 19, and the audit now fails the build on any
finding. Coverage limit below still stands.

## Unresolved questions

- Whether to make the audit strict by default once the fixes land.
- Whether to seed plans, coupons, subscriptions and payments so the empty screens gain sheets to audit.
