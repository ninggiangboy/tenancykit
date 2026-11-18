---
title: "Suite Console — microfrontend monorepo for Platform and Billing"
description: "A frontend/ monorepo with two shells (Platform, Tenant) that load every UI as a Module Federation 2 remote from a dynamic registry in Platform; v1 ships remote-platform and a full remote-billing."
status: completed
priority: P1
effort: "14 phases, about 55 person-days"
branch: dev
tags: [feature, frontend, backend, api, infra]
blockedBy: []
blocks: []
created: 2026-11-17
---

# Suite Console

## Overview

The suite has ten API-only backends and no screen. This plan builds the Suite Console in `frontend/`: one pnpm + Turborepo
monorepo, two shells split by audience and hostname, and every UI an independently deployable Module Federation 2
remote. Platform gains a registry of UI remotes: each remote's CI registers a version, an operator enables or disables it
and picks the active version, and the shell receives only the remotes its caller may use.

Scope was fixed in the `/ak-advise` interview of 2026-11-17 (memory: `suite-console-decisions`). Preview:
`plans/reports/advise-261117-2050-suite-console-preview.html`. The plan is **HOLD SCOPE**: it delivers everything the
interview confirmed and adds nothing. It replaces the "Suite Console is a follow-up" note in
`plans/261112-0726-enterprise-suite-foundation/plan.md` (phase 14) and the old web phases of the Billing plan.

## Outcome contract

**Goals**

1. A Platform operator and a Tenant admin each get a console on their own host, built from one monorepo.
2. An app's UI ships on its own: deploying `remote-billing` never rebuilds or redeploys a shell.
3. What a user sees follows the tenant's app entitlement and the user's permissions, decided by the backend.

**Requirements**

1. `frontend/`: pnpm 12 + Turborepo, React 19, Vite + Module Federation 2, shadcn/ui + Tailwind 4, TanStack Router, Query
   and Table, generated API client, i18next (English and Vietnamese), Biome, Vitest, Playwright (stack table below).
2. Two shells: Platform Shell (platform host) and Tenant Shell (tenant hosts). Both are built from one `shell-core`.
3. Every UI is a remote: `remote-platform` and `remote-billing`.
4. `remote-platform` covers tenants (with domains and app entitlements), users, roles, tenant systems with their
   credentials, and the UI remote registry. `remote-billing` covers all four groups: core money flow, pricing and
   coupons, wallets and usage, webhooks and Stripe.
5. Shared packages: `shell-sdk`, `auth`, `ui`, `api-client`.
6. Platform backend: the UI remote registry (new tables, API, permissions, contract) and a read endpoint for a tenant's
   app entitlements. CI registers a version through a Platform service client; an operator toggles and picks the active
   version; the shell feed is filtered on the server. A version registered by CI is never active by itself: the
   operator activates it.
7. Each remote has its own CI pipeline: build, publish immutable assets, register the version.
8. The UI follows the Suite Console design system (section below): tokens, components, layout and the keyboard-first,
   peek-not-navigate, state-in-the-URL principles.

**Done means** (each item is checked by a command or an E2E test; phase 13 owns them)

- A new `remote-billing` build deployed alone and activated by the operator is loaded by the Tenant Shell after a reload;
  no shell build or deploy ran.
- Disabling the Billing entitlement of a tenant removes the Billing menu for that tenant after a reload.
- An operator disabling a remote in Platform Admin makes the shell stop loading it; re-pointing the active version rolls
  back in under a minute without a build.
- Every Platform and Billing screen in requirements 4 works against the real backends through Caddy.
- A tenant user without a permission gets `403` from the backend and does not see the control in the UI.
- The registry rejects an entry URL outside `/console/remotes/<name>/<version>/`.
- The generated token variables equal `tokens.json` of the design system (a test), and on every list page Cmd or Ctrl K, `/`,
  j and k, Enter, x and Esc work, a row opens a record sheet whose URL can be reloaded, and filters, sort and tabs are in
  the URL.
- The registry OpenAPI and permissions are in `contracts/`, the placeholder `frontend/README.md` is replaced, and the docs no
  longer call the Console a follow-up.

**Non-goals**: a notification bell, tenant switching (a user belongs to one tenant); UIs for survey, workflow, booking, crm, loyalty, mailroom, attribution, helpdesk, customer; Customer
Portal; per-tenant rollout or canary; custom domains; production publishing of assets (S3 or CloudFront) and the ECS
deployment of `frontend/`; backend changes other than the registry, the entitlement read and the Keycloak redirect sync.

**Constraints**: two shells split by platform and tenant host; every UI is a remote, Platform included; registry in
Platform; CI self-registers with an existing-style service client; `ui` is not a shared runtime singleton; auth only in
the shell; list screens use the list-query contract; v1 has one real remote per shell, so multi-remote composition is
not proven (accepted trade-off). No plan ids, phase numbers or finding codes in code comments, test names or commits.

## Stack (npm versions checked 2026-11-17)

| Layer | Choice |
|---|---|
| Monorepo | pnpm 12.21, Turborepo 2.11, Node 24 |
| Language | TypeScript 7.0.2 (native `tsc`) for typechecking only; 6.0.3 is the verified fallback (phase 1) |
| Bundler and federation | Vite 8 (Rolldown) + `@module-federation/vite` 1.24.1, proven on a production build in phase 1; Rsbuild is not needed |
| UI | React 19.3, shadcn CLI 4 (Radix, `cmdk`), Tailwind 4.3 with the design system tokens, Lucide, Sonner, Recharts 3; Inter, Be Vietnam Pro and Geist Mono self-hosted with Fontsource |
| Routing and data | TanStack Router (already decided in `docs/architecture.md`), TanStack Query 5, TanStack Table 9 |
| API client | `@hey-api/openapi-ts` 0.99 (types, SDK, Query hooks, Zod) from `contracts/openapi` |
| Forms | React Hook Form 7 + Zod 4 |
| i18n | i18next 27 + react-i18next 18, English and Vietnamese; one isolated instance per remote, locale from the shell |
| Auth | `oidc-client-ts` 3.5 in the shell only, Keycloak client `suite-console` (PKCE already enabled) |
| Quality | Biome 2, Vitest 5, Testing Library, MSW 3, Playwright 1.65 |
| CI | GitHub Actions `frontend.yml` plus one workflow per remote |

## What scouting changed in the design

| Finding (evidence) | Consequence |
|---|---|
| Caddy sends every unmatched path to Billing and serves Platform at the root of `platform.localhost`; the smoke scripts call `platform.localhost/api/...` (`ops/caddy/Caddyfile`, `ops/smoke/suite-smoke.sh`) | The console lives under the `/console/` prefix on every host, like every other app, so no existing route moves. Shell at `/console/`, remotes at `/console/remotes/<name>/<version>/`. |
| Keycloak client `suite-console` already exists: public, PKCE S256, audience mappers for every app, `tenant_code` mapper; redirect URIs only `localhost:5173` and `platform.localhost:5173` (`ops/keycloak/realm-export.json`) | Reuse it. Keycloak accepts a wildcard only at the end of a redirect URI, so a tenant host cannot be wildcarded: Platform syncs the client's redirect URIs from tenant domains (phase 4). |
| Platform has no read for a tenant's app entitlements; `TenantResponse` has no apps and only `PUT /tenants/{id}/apps/{app}` exists | Phase 3 adds `GET /api/v1/tenants/{tenantId}/apps`, needed by the tenants screen and by the entitlement toggle. |
| Service clients are config entries (`platform.service-clients` in `application.yml`): "one entry and a secret hash, no Java code moves" | CI registers through a new `frontend-ci` service client with scope `platform:ui-remote:register`. |
| `/api/v1/me` returns scope, tenant id and permission codes | The shell and remotes gate controls from it; the backend still decides. |
| Billing list parameters are flat literal names such as `filter[created_at][eq]`, with `x-required-permission` and `x-allowed-callers` on each operation (`contracts/openapi/billing.yaml`) | `api-client` builds those keys with one helper and generates an operation-to-permission map from the OpenAPI extension. |
| Generated code is committed in this repo (jOOQ, OpenAPI) and compared in CI | `api-client` output is committed and a CI step fails on drift. |

## Design system

Source: the user's own artifact "Suite Console" (https://claude.ai/artifact/CXbCvJyZEvS7Zx2NcQHCFi), version
`1794921831-f0e0`, read on 2026-11-17. A pinned snapshot of its `README.md`, `tokens.json` and component API
(`index.d.ts`) is in `plans/261117-1456-suite-console/reports/design-system/`; the samples (`InvoicesScreen`,
`CommandScreen`, `TenantAppsScreen`, `RoleScreen`, and one `preview.html` per component) stay in the artifact and are read
with the Artifact tool when a phase builds the matching screen.

**How it is used.** Ported, not bundled: the artifact ships a React 18 plain-JavaScript `window.Suite` bundle, while the
console is React 19 with TypeScript and Module Federation singletons. `@suite/ui` (phase 6) keeps the token names,
values, component names and props, and rebuilds the components on shadcn primitives. The token names already match
shadcn's CSS variables one to one.

**Rules every screen follows** (the design system's UX principles, made acceptance criteria):

1. Keyboard first: Cmd or Ctrl K palette, `/` focuses the list search, j and k or arrows move, Enter opens, x selects,
   Esc closes the top layer; every shortcut is shown beside its action.
2. Peek, do not navigate: a row opens a `Sheet` beside the list (own URL, previous and next inside it).
3. Undo over confirm: a reversible action applies at once with an Undo toast; only an irreversible one (void, revoke,
   erase, disable an app, disconnect) opens a `Dialog` that lists real counts of what stops and what is kept, and asks for
   the tenant code or record id when it crosses a tenant or erases data. Each phase classifies its actions.
4. A `Switch` is optimistic with `busy`, flips back with a toast on failure.
5. Never a dead end: empty states say why and what fills them, "no results" names the filters to remove, errors offer
   Retry, loading keeps the layout with skeleton rows after 150 ms.
6. Forms with many edits use the floating `UnsavedBar` (count, Discard, Save with Cmd or Ctrl S).
7. Every identifier is a `CopyId`; money is an integer in minor units shown with its currency code; times are UTC and the
   header says so; enum values are humanized by `StatusBadge`, always a dot plus a word.
8. State lives in the URL: tabs, filters, sort, page size and the open sheet.
9. Content: sentence case, a verb and an object on buttons, a confirming button repeats the verb, past tense in toasts,
   no apologies, no exclamation marks, no emoji. Applies to the `en` and `vi` catalogs alike.
10. Monochrome first; colour only where it means something (status dots, one blue for focus, links and selection).

**Departures from the first draft of this plan.** The `platform` and `tenant` themes are dropped (the design system has
light and dark; scope is shown by the amber line and pill). The notification bell and tenant switching are not built. The
design system's app list includes apps whose UIs are out of scope; its `AppMark` still supports them, and the sidebar shows
only the remotes the registry returns.

## Architecture

```text
browser  http://<tenant>.<base>/console/        http://platform.<base>/console/
            Tenant Shell                              Platform Shell
               |  GET /platform/api/v1/console/remotes (filtered by tenant entitlement, audience, enabled)
               |  loads /console/remotes/<name>/<version>/mf-manifest.json  (integrity checked)
               v                                          v
        remote-billing      remote-platform  (mounted in both shells; pages follow /me permissions)
Caddy: /console/* -> console-static (per host chooses the shell); /platform/* -> Platform; root -> Billing
CI of a remote: build -> copy to console-static volume -> POST /ui-remotes/<name>/versions (service client frontend-ci)
```

**Composition, fixed by the phase 1 spike** (`reports/spike-federation.md`). The shell owns the router and gives a
remote a splat route (`/console/r/$remote/*`) plus the shared browser history and its basepath; the remote builds its
own router on that history, so a remote navigation never re-renders the shell. The shell attaches the registry's
`sha384` integrity to the manifest request through a Module Federation runtime `fetch` plugin, so only verified manifest
bytes are parsed; chunk bodies are held by the same-origin allow-listed entry path, immutable version directories and
`script-src 'self'`. Building a new remote version does not touch the shell build.

## Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | [Federation spike](./phase-01-federation-spike.md) | Done |
| 2 | [Monorepo foundation](./phase-02-monorepo-foundation.md) | Done |
| 3 | [UI remote registry backend](./phase-03-ui-remote-registry-backend.md) | Done |
| 4 | [Keycloak redirect sync](./phase-04-keycloak-redirect-sync.md) | Done |
| 5 | [Edge and remote deploy](./phase-05-edge-and-remote-deploy.md) | Done |
| 6 | [Shared packages](./phase-06-shared-packages.md) | Done |
| 7 | [Shells](./phase-07-shells.md) | Done |
| 8 | [Remote platform](./phase-08-remote-platform.md) | Done |
| 9 | [Remote billing: money core](./phase-09-remote-billing-money-core.md) | Done |
| 10 | [Remote billing: pricing and coupons](./phase-10-remote-billing-pricing-coupons.md) | Done |
| 11 | [Remote billing: wallet and usage](./phase-11-remote-billing-wallet-usage.md) | Done |
| 12 | [Remote billing: webhooks and Stripe](./phase-12-remote-billing-webhooks-stripe.md) | Done |
| 13 | [End-to-end gate](./phase-13-end-to-end-gate.md) | Done |
| 14 | [Docs and closure](./phase-14-docs-and-closure.md) | Done |

## Dependencies between phases

- Phase 1 gates everything: its decisions (bundler, router composition, integrity mechanism, TypeScript version) are
  written back into this file and the affected phases before phase 2 starts.
- After phase 1, **phases 3 and 4 (Java) can run in parallel with phases 2, 5 and 6 (frontend and ops)**: they own disjoint
  directories (`platform/`, `contracts/`, `ops/keycloak` against `frontend/`, `ops/console`, `ops/caddy`).
- Phase 7 needs 2, 3, 5, 6. Phase 8 needs 7 and phase 3's entitlement read. Phases 9 to 12 need 7 and the
  `remote-billing` scaffold created in phase 9; they own disjoint feature folders and may run in parallel after phase 9.
- Phase 13 needs everything; phase 14 closes.

## Test strategy

Unit and component tests with Vitest, Testing Library and MSW next to the code. Backend phases add Java unit,
Testcontainers integration and OpenAPI contract tests, and keep ArchUnit green. Phase 13 adds Playwright against the
compose stack for every Done-means item. Each phase lists its own gate command; broaden to the full
`pnpm turbo run lint typecheck test build` and the Gradle `build` of `platform/` whenever a shared package or contract
changed.

## Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| Vite 8 (Rolldown) with `@module-federation/vite` fails or misbehaves at runtime | Medium | **Retired in phase 1**: a production build renders, shares one React instance and loads two remote versions. No fallback taken |
| TanStack Router does not compose across a runtime remote boundary | Medium | **Retired in phase 1**: shape A composes (shell splat route, remote router on the shell's history); deep links, back and forward, no shell re-render. The router owner decision stands |
| Registry is a code-injection surface | High impact | Entry path allow-list, manifest hash verified by the shell with a runtime `fetch` plugin (phase 1 measured that only the manifest is covered), CSP `script-src 'self'`, write access only for the `frontend-ci` scope and the operator permission |
| Per-tenant Keycloak redirect sync drifts or fails | Medium | Idempotent sync on domain add and remove plus a startup reconciler; realm export seeds the dev hosts |
| A remote deployed with an incompatible `shell-sdk` crashes the shell | Medium | Registry stores `sdkMajor`; the shell refuses a mismatch and shows an error instead of mounting |
| Every remote must ship two string catalogs; a missing key shows a raw key | Medium | `missingKeyHandler` throws in tests; each page is rendered in both languages; API error messages stay English (recorded limit) |
| The design artifact changes after the snapshot, or a ported component drifts from it | Medium | Version id pinned; token test against `tokens.json`; each component checked against its artifact preview in light and dark |
| Separate Radix copies per remote stack overlays wrongly | Low | One documented z-index scale in `ui`; a phase 13 check opens a remote dialog over a shell sheet |
| TypeScript 7.0 breaks a tool | Low | Used only for `tsc` typechecking; fall back to 6.x per the spike |
| CSS duplication or ordering across remotes bundling `ui` | Low | Theme tokens live in the shell as CSS variables; remotes ship utilities only |
| One real remote per shell leaves composition unproven | Accepted | Recorded trade-off; phase 13 still proves deploy independence, entitlement and rollback |

## Extension trace

The next credible variant is a second app remote (for example `survey`). It adds `frontend/remotes/survey`, one remote CI
workflow, one registry row and the tenant's existing entitlement. The shells, the registry API, `shell-sdk` and the
deploy script do not change. Phase 13 proves this claim by deploying a throwaway second remote built from the same
template and removing it. No plugin system, registry abstraction or remote factory is built for it.

## Decisions taken at validation (2026-11-17)

1. Tenant admins manage their own users, roles and tenant systems through the same `remote-platform`, mounted in the
   Tenant Shell and driven by `/me` permissions. The registry stores a set of audiences per remote.
2. CI registers a version but never activates it; the operator activates each version in Platform Admin. A deploy
   therefore goes live only after that click; rollback is the same click on an older version.
3. Platform syncs the `suite-console` redirect URIs from tenant domains (phase 4); a fixed auth origin was declined.
4. The console speaks English and Vietnamese through i18next, one isolated instance per remote so no extra shared
   singleton couples remotes to a shell version; the shell owns the language choice and passes `locale` in the context.
   Messages that come from the API stay in the language the API returns.

5. The UI follows the Suite Console design system by the user (see "Design system"), ported to React 19 and Tailwind 4;
   its keyboard and peek principles are acceptance criteria, which added about 9 person-days.

## Decisions taken at the spike (2026-11-17)

1. Bundler: Vite 8 + `@module-federation/vite` 1.24.1. Proven on a production build with one React instance and two
   remote versions; Rsbuild stays unneeded.
2. Router: shape A. The shell owns the router; a remote receives the shared history and basepath and creates its own
   router. Deep links, back and forward, and an un-re-rendered shell all pass.
3. Integrity: a Module Federation runtime `fetch` plugin attaches the registry hash to the manifest request. The
   manifest is the covered unit; chunks are guarded by the same-origin allow-list, immutable directories and CSP.
4. TypeScript 7.0.2 (native) typechecks the shells, remotes and generated client; 6.0.3 is the verified fallback.
5. The spike found `MeController` looking a caller up by display name instead of Keycloak subject; phase 3 corrects it
   and covers it with a test before phase 7 depends on `/me`.

## Open questions

None blocking. The spike answered all six questions in `reports/spike-federation.md`; no router-composition failure
appeared, so no owner decision is outstanding.

## Implementation notes (2026-11-18)

Phases 8 to 12 are implemented and the remote gate is green:
`pnpm --filter @suite/remote-platform test`, `pnpm --filter @suite/remote-billing test`,
`pnpm turbo run typecheck test build`, and `pnpm exec biome ci .` all pass. Each page has an MSW-backed flow and is
rendered once in Vietnamese; a catalog test fails when a key exists in one language only.

What changed against the plan, and why:

- **Contract fix, not a scope change.** `/api/v1/roles` POST declared the user `CreateRequest`; the backend takes
  `{name, permissions}`. A `RoleCreateRequest` schema now matches the controller, and the generated client was regenerated.
- **The usage aggregate endpoint returns totals per metric for a window (`TotalResponse[]`), not a time series.** The
  aggregate screen therefore charts those totals with a table fallback instead of inventing buckets.
- **Customers expose no `version`.** Update and deactivate send no `If-Match`; the accepted version handling remains on
  the screens whose responses carry one (tenants, users, roles, entitlements, UI remotes, plans).
- **Promotion codes are deactivated from the coupon's code list**, because there is no global promotion-code list; the
  wallet-scoped redemption screen owns redeem, attach and revoke.
- **Delivery detail shows status, attempts, response code and last error in place**, and resend creates a new delivery;
  the raw delivery body is not rendered.

Remaining operational checks (not code): the first real `ops/console/deploy-remote.sh` publish needs compose and the
`frontend-ci` secret.

## Implementation notes (2026-11-18, phases 13 and 14)

The gate is green on the compose stack: `pnpm --dir frontend --filter @suite/e2e test` runs ten Playwright tests in
about four minutes and every phase 13 row passed (deploy of a remote alone with unchanged shell asset URLs, the
entitlement removing a menu, the operator's disable and rollback under a minute, every screen against the real
backends, a missing permission with the control absent and `403` from the API, a refused entry path, Vietnamese on
both remotes and in the design system's own strings, the keyboard and URL rules, the token values, stacking with
focus return, axe in light and dark, and the extension trace with its registry row and files removed afterwards).
`frontend.yml`'s `pnpm turbo run lint typecheck test build` and the platform's Gradle `build` stay green.

The gate found defects in the earlier phases' code; each is fixed at its owner and covered where a test can:

- **Billing's replica could not write `tenant_systems`.** The table kept only its tenant policy, so the replica's
  Platform-scope upsert was refused, `billing-worker` died during its rebuild, and entitlement replication stopped.
  `V32__replica_writer_policy.sql` adds the same `platform_writer` policy `user_grants` already had.
- **The registry required `enabled` on every write.** The screen's activation sent only the version, so activating was
  `400`. Activation now carries the remote's current enabled state (both for the click and its undo).
- **A Tenant's default subdomain was never registered with Keycloak.** Only an added domain was, so a new Tenant could
  not sign in until the five-minute reconciler ran. Tenant creation now registers its host after the commit.
- **The console port was applied by the reconciler only.** A domain add registered `http://<host>/console/*`, without
  the published port. The port now belongs to the Keycloak client, so both paths produce the same target.
- **The `suite-console` client's origins grew without bound.** Keycloak copies the web origins into every token as
  `allowed-origins`; one entry per Tenant host pushed an access token past Tomcat's 8 KB header limit after a few
  dozen Tenants. The origins are now the single `*` entry — a public PKCE client gains nothing from a per-host list —
  and the redirect URIs stay exactly the hosts that exist.
- **Events could arrive out of order.** Version-7 ids carried random low bits within a millisecond, and the outbox
  claims rows by `(next_attempt, id)`, so a `tenant-domain.changed` could be applied before its `tenant.changed` and
  dead-letter after its retries. The id generator now counts inside the millisecond, so ids sort in creation order.
- **List pages declared sort fields the API rejects.** Clicking a sortable header produced "the field … cannot". Every
  screen's `sortable` is now the intersection of its columns and its endpoint's allowed sort fields.
- **Selection and keyboard.** `x` only worked with focus inside the table; j/k, Enter and x are now page-level (fields,
  menus and dialogs excluded), and every list has a default bulk action (copy the selected identifiers) so the
  `BulkBar` appears.
- **Small design-system defects the axe and keyboard checks found.** The bulk bar's action was white on white, a
  dialog without a Radix `Trigger` did not return focus to its opener, `Tabs` emitted an `aria-controls` to a panel
  that does not exist, and `CopyId`'s and the language button's accessible names did not contain their visible text.
- **The UI's own strings ignored the locale.** `UiMessagesProvider` was never mounted, so "1 selected" stayed English
  inside a Vietnamese screen; the shell and both remotes now provide it from the context's locale.
- **A reload dropped the query string.** The sign-in return path was the pathname alone, so a reloaded record sheet
  (whose `open` is in the URL) closed itself; the shell returns to path and search.
- **The invoice list had no search field** (the design system's one-line toolbar needs one; the endpoint's
  `filter[number][starts_with]` supports it), and **Stripe's screen showed "That did not work"** for the documented
  `404` of an unconnected account instead of a "not connected" state.

Two limits the gate records rather than hides: replica lag for a brand-new Tenant can reach about a minute while the
consumer retries a child event that overtook its parent (the fixture budgets for it), and a list screen without a text
column (`payments`) has no search box for `/` to focus.

