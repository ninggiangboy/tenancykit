---
title: "Console UI polish: what the audit missed and what surprised us"
date: 2026-11-21
summary: "Seven-phase console polish delivered; the screen audit passed while the console was broken, a backend helper was loosened, and one plan premise was wrong."
---

# Console UI polish: what the audit missed and what surprised us

## What happened
The console polish plan (plans/261121-0826-console-ui-polish) was delivered in seven phases on dev, uncommitted. The
full end-to-end suite passes (48 tests, 10.9 minutes) and the screen audit reports 0 findings. Evidence per rule is in
the plan's reports/ folder.

## What surprised us
- **Why the audit passed while the console was broken.** 12-screen-audit measured sizes, colours and how an overlay
  opens. It never closed an overlay, never asked which of two layers was on top, never measured what scrolls, never made
  a request fail inside a sheet and never used a control. Every fault in the plan existed with 0 findings. The new
  13-interaction-audit proves behaviour, one describe per rule, and each check was run against the build before its
  fix and failed there.
- **The backend change is in a shared helper.** The entitlement list reports an app a tenant never changed as version 0
  and the write endpoint refused the If-Match "0" it implied (412). ExpectedVersion.checkAbsent() now accepts "0" for a
  row that does not exist. It also governs Loyalty's program save; its tests pass. Fixing it in the console instead
  would have left the API contradicting itself for every other client.
- **The plan's premise about list queries was wrong.** It said lists use keepPreviousData; only option lookups did, so a
  filter change blanked the table. The refresh bar only works because phase 4 added placeholderData to 17 list hooks.
- **A Radix dialog without a Trigger returns focus to nothing**, so sheets needed their own opener memory and table rows
  needed to be click-focusable.
- **Harness faults surfaced by the full run**: a cached Keycloak token expired during a run longer than five minutes,
  an 11-mobile race with the drawer closing on a route change, and false "no enter animation" findings when extra
  compose containers loaded the machine.

## Decision
Keep the behaviour audit as the gate for UI rules; a rule without a test is not a rule. Four tables (invoices, wallets,
usage events, webhooks) give up sticky titles because they cannot fit at 768 to 1024px.

## Next steps
- The owner takes the Chrome Performance trace on real hardware; headless numbers only say nothing regressed.
- 33 backend app-boot tests fail on RepositoryPaths (repo layout moved under backend/); unrelated, needs its own fix.
- Commit the work; the tree also holds the previous session's uncommitted edits.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
