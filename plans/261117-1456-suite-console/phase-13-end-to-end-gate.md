---
title: "Phase 13: End-to-end gate"
status: done
priority: P1
effort: "3d"
dependencies: [8, 9, 10, 11, 12]
---

# Phase 13: End-to-end gate

## Goal

Every Done-means item of the plan is proven by a Playwright test against the real compose stack, and the same suite runs
in CI beside the existing suite smoke.

## Tests (`frontend/e2e/`)

| Done-means item | Test |
|---|---|
| Deploy of `remote-billing` alone is picked up with no shell change | Record the shell's asset hashes, build and deploy a new `remote-billing` version with `ops/console/deploy-remote.sh`, activate it as the operator (Platform shell UI), reload the Tenant Shell, assert the new version marker renders and the shell hashes are unchanged |
| Entitlement removes the menu | Two tenants, one with Billing enabled and one without; assert the menu entry exists only for the first; disable the first through the Platform API, reload, assert it disappears |
| Operator disables a remote, and rollback | Disable `billing` in the Platform shell UI, reload the tenant shell, assert Billing is gone; enable it and re-point the active version to the previous one, assert the previous marker, all in under a minute and with no build |
| Screens work against real backends | One happy path per page group of `remote-platform` and the four `remote-billing` groups |
| Permission is enforced and hidden | A tenant user without `billing:invoice:...` write: control absent in the UI and a direct API call returns `403` |
| Registry rejects a foreign entry path | `POST` a version whose `entryPath` is outside `/console/remotes/<name>/<version>/`, assert `400`, nothing registered |
| Extension trace | Build a throwaway second remote from the same template, deploy and register it, assert the shells need no change and it appears for its audience, then remove its registry row and files |
| Language | Switch the shell to Vietnamese; a `remote-platform` page and a `remote-billing` page render Vietnamese text, and the choice survives a reload |
| Design system rules | On an invoice list and the role page: Cmd or Ctrl K opens the palette, `/` focuses search, j and k move, Enter opens a sheet whose URL reloads to the same record, x selects and the `BulkBar` appears, Esc closes the top layer; filter, sort and tab are in the URL; the generated token variables equal `tokens.json`; open a remote `Dialog` over a shell `Sheet` and assert stacking and focus return; axe finds no serious violation in light and dark |
| Console health | No CSP violation, no failed request and no console error during the suites above |

## Files

- Create: `frontend/e2e/**` (config, fixtures, tests), `frontend/e2e/support/stack.ts` (provision tenants, users and entitlements
  through the Platform API with an admin token; never real credentials in the repo).
- Modify: `.github/workflows/suite.yml` (run the console E2E after the existing smoke on the same running stack; read the
  file first and reuse its `.env` writing step), `frontend/README.md` (how to run the E2E locally).

## Steps

1. Read `ops/smoke/suite-smoke.sh` for how it obtains admin tokens and provisions a tenant; reuse the same approach.
2. Write fixtures and the tests above, deterministic and independent: each test creates its own tenant codes.
3. Run locally against the compose stack; fix defects in the owning phase's code, not by weakening a test.
4. Wire into `suite.yml`; keep the existing smoke job unchanged.
5. Stop every process and container started for the run.

## Todo

- [x] Fixtures and stack provisioning (`support/stack.ts`: a fresh tenant per test, its host added as a domain so
      Keycloak accepts the sign-in, replica waits, seeded Billing records through a tenant-system credential)
- [x] Independent-deploy test (`01-independent-deploy.spec.ts`, activates through Platform Admin and asserts the
      shell's asset URLs are unchanged)
- [x] Entitlement and operator-toggle tests with rollback timing (`02`, `03`; both under a minute, measured)
- [x] Screen happy paths for all page groups (`04`: the five Platform pages and the four Billing groups)
- [x] Permission and registry-rejection tests (`05`)
- [x] Language switch test (`07`, including a design-system string)
- [x] Keyboard, URL state, token and accessibility tests (`08`, on the invoice list and the role page, axe light/dark)
- [x] Extension trace test (`09`, removes the registry row and the published files afterwards)
- [x] CI wiring, local run green (`suite.yml` builds and stages the console on the same stack as the smoke; ten tests
      pass in about four minutes)

## Verification

```bash
cd frontend && pnpm --filter e2e test
```

Exit 0 locally and in the `suite` workflow; the report lists every row above as passed.

## Risks

Flakiness from login redirects and cold containers; the tests wait on observable state (a version marker), never on
fixed sleeps. The rollback timing assertion measures wall time between the operator action and the visible change.

## Security

Test users and tenant codes are generated per run; no password or secret is committed, and tokens never appear in
Playwright traces (traces are off in CI by default).
