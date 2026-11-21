# Full run, 2026-11-21

## Commands and results

| Command | Result |
|---|---|
| `cd backend/libs/core && ./gradlew build` | passes |
| `cd backend/services/platform && ./gradlew build` | fails: 3 of 164 `app-boot` tests, all `RepositoryPaths` "repository root not found" |
| `cd backend/services/loyalty && ./gradlew build` | fails: 30 of 354 `app-boot` tests, same cause |
| Platform `ConditionalUpdateHttpIntegrationTest` and the tenancy tests; Loyalty `ProgramApiIntegrationTest` | pass |
| `cd frontend && pnpm install --frozen-lockfile` | passes |
| `pnpm turbo run lint typecheck test build` (filtering out the e2e package) and `biome check .` | pass |
| `docker compose up -d --build --wait` | passes |
| Shells and both remotes deployed (a build per remote with its own version, activated) | passes |
| `pnpm --dir frontend --filter @suite/e2e test` (specs 01 to 13) | **48 passed, 0 failed, 10.9 minutes**; the screen audit reported 0 findings |

The 33 backend failures are not caused by this plan. The tests resolve the repository root by looking for a `contracts`
and a `platform` directory side by side, and the code now lives under `backend/`, so every test that reads a repository
file (the schema, the OpenAPI document, the event catalogues, the architecture scan) cannot start. `ExpectedVersion` is
not in any of those paths.

The unit tests of the frontend packages are timing sensitive when every package runs at once: single tests in
`remote-billing` and `remote-platform` failed in several runs under `turbo`'s default concurrency and passed alone and under
`--concurrency=2`. They were not changed.

## Run time of the new file

`13-interaction-audit.spec.ts` takes 3.3 minutes, under the five-minute line at which the plan says to share the tenant
provisioning (it already shares one tenant and two signed-in pages across every test).

## Does every rule's check fail without its fix?

Each check was run against the console as it stood before its phase, and fails there. This is the same evidence as
reverting one fix at a time, at the grain of a phase, because every phase's fixes shipped together.

| Rule (the `describe` of the check) | Fails on the build before | Evidence |
|---|---|---|
| The frame and its scrolling: the page scrolls inside the viewport under sticky titles | yes: 9 screens scroll the document | `phase-01-before.md` |
| The frame: a table fits at four widths or scrolls sideways without sticky titles | passes before (nothing was sticky); **fails when `stickyHeader={false}` is removed from invoices again** (the table is clipped at 768 and 1024px) | reverted and run on 2026-11-21 |
| The frame: the sidebar keeps the account row in view | yes | `phase-01-before.md` |
| The layer order: a menu opened from a sheet, and a toast, on desktop | yes | `phase-01-before.md` |
| The layer order: a menu opened from a sheet, phone | yes | `phase-01-before.md` |
| The layer order: a toast on a phone | passes before (the toast stands under the top bar there); kept as a guard |
| Leaving: desktop and phone | yes: the sheet is gone on the first frame and focus is lost | `phase-02-before.md` |
| Write progress: a switch, a button | yes: opacity 0.5, 53.7px to 75.4px | `phase-04-before.md` |
| Write failure | yes | `phase-04-before.md` |
| Read failure inside a sheet | yes | `phase-04-before.md` |
| List failure and refresh (both) | yes | `phase-04-before.md` |
| Search requests, facet rows | yes: 8 requests, a dead click | `phase-05-before.md` |
| Rendering cost (four scenarios) | pass before and after: guards for `performance-before-after.md` |
| The first entitlement | yes (412) | `phase-01-before.md` |
| Icons (2), alignment (2), one look for one control (2), Vietnamese strings | yes | `phase-06-before.md` |

## Screenshots against the baseline

The baseline was captured before phase 1 (`10-visual` and `12-screen-audit`, 264 and 289 images). After the change,
`10-visual` gives 264 images, 49 of them of a different size, and the audit 287 (4 names that did not recur). A pixel
comparison cannot be the verdict: every run provisions a new tenant, so each capture differs by a tenant code, ids and
timestamps. The differences were reviewed by eye on the screens each phase touched (the invoices list, the UI remotes
sheet, the wallet sheet, the phone webhook sheet, a dark compact list) and every visible difference traces to a phase:

- Desktop captures are the height of the viewport instead of the height of the document, which is the size change on
  49 full-page images, and the account row and sign-out button show at the foot of the sidebar (frame, phase 1).
- The sheet scrim is no longer blurred; the page behind a sheet is sharp (phase 2).
- The theme control shows a sun, a moon or a monitor, and the pricing plans, coupons and Stripe nav items have their own
  icons (phase 6).
- Detail rows are 40px tall and share a baseline (phase 6).
- Versions of a remote are one height, with the active one on the selection background and its badge in the same column
  as the other rows' buttons; the ledger's reference wraps under its entry and the amount and Reverse sit in their own
  columns; deliveries, invoice items and payments and tenant apps use the same rows (phase 6).
- A select draws a chevron and the dialog footer buttons are the control height (phase 6).
- Tables on `md` and up keep their column titles under the top of the page, and four tables (invoices, wallets, usage
  events, webhooks) scroll sideways instead and do not (phase 1).

One difference was a defect and was fixed before the last run: on a 390px phone the new delivery rows squeezed their
text to four lines beside the Resend button. The trailing part of a row now takes its own line when the text would
have less than 10rem.

No difference was left unexplained in the captures that were reviewed; the other roughly 500 were not opened one by one.

## Performance

See `performance-before-after.md`. All four scenarios meet the headless acceptance numbers. The Chrome Performance trace
on real hardware, which `plan.md` assigns to the owner, has not been taken, so whether the console still feels slow
there is not known.

## Unresolved

- The real-hardware trace above.
- The 33 backend tests that cannot find the repository root (not this plan's change; a separate fix).
- Nothing was committed, and the working tree still holds the previous session's uncommitted edits next to this plan's.
