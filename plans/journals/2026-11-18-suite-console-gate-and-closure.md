---
title: Suite Console gate and closure
date: 2026-11-18
summary: "Phases 13 and 14: the Playwright gate, the defects it found, and the docs"
---

# Suite Console gate and closure

## What landed

Phases 13 and 14 of `plans/261117-1456-suite-console`: the end-to-end gate in `frontend/e2e` and the documentation
that matches what shipped. Ten Playwright tests pass against the compose stack in about four minutes, and the plan is
closed at 14/14.

The gate covers, one file per row of the phase table: a remote deployed and activated with no shell change (the
shell's asset URLs are byte-identical afterwards), the entitlement removing a menu, the operator disabling a remote
and rolling back under a minute, every Platform and Billing screen against the real backends, a missing permission
with the control absent and `403` from the API, a rejected entry path, Vietnamese on both remotes and in the design
system's own strings, the keyboard and URL rules with axe in light and dark, and the extension trace, which builds a
second remote from the template and removes its registry row and files afterwards.

## What the gate found

Each defect is fixed at its owner:

- Billing's replica could not write `tenant_systems` (tenant policy only), so `billing-worker` died on rebuild and
  entitlements stopped replicating. `V32__replica_writer_policy.sql` adds the Platform-scope policy that
  `user_grants` already had.
- The registry's PUT required `enabled`, while the screen's activation sent only the version; activation was `400`.
- Tenant creation never registered its default subdomain with Keycloak, and a domain add registered the host without
  the console port; a new tenant could not sign in until the five-minute reconciler ran.
- Keycloak writes the client's web origins into every token as `allowed-origins`; one per tenant pushed access tokens
  past Tomcat's 8 KB header limit. The origins are now the single `*`; the redirect URIs stay the hosts that exist.
- Version-7 ids had random low bits within a millisecond, so the outbox could publish a child event before its
  parent and the child dead-lettered after its retries. The generator now counts inside the millisecond.
- Every list page declared sort fields the API rejects; each screen's `sortable` is now its endpoint's allowed set.
- `x` only worked with focus inside the table; j/k, Enter and x are page-level, and every list has a default bulk
  action so the BulkBar appears.
- Smaller design-system defects: a white-on-white bulk action, a dialog that did not return focus without a Radix
  Trigger, `Tabs` pointing at a panel that does not exist, accessible names missing their visible text, and the UI's
  own strings ignoring the locale.
- The shell's sign-in returned to the pathname only, so a reloaded record sheet lost its query string.
- The invoice list had no search field, and Stripe's screen showed an error for an account that is not connected yet.

Test infrastructure: the e2e package is out of `turbo run test` (the gate has its own workflow step), and the jsdom
packages allow 20 s per test so a busy machine does not fail interaction tests.

## Verification

`pnpm exec biome ci .` and `pnpm turbo run lint typecheck test build --filter=!@suite/e2e` in `frontend/`,
`pnpm --dir frontend --filter @suite/e2e test` against the stack, and the Gradle builds of `platform/` and `billing/`
(including the new migration). `ops/smoke/console-smoke.sh` still covers the edge and the publish flow.

## Limits recorded

Replica lag for a brand-new tenant can reach about a minute when the consumer retries a child event that overtook its
parent; the fixture budgets for it. `payments` has no text column, so its list has no search box for `/` to focus.
Production asset publishing and the ECS deployment of `frontend/` remain out of scope.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
