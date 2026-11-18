---
title: "Phase 7: Shells"
status: completed
priority: P1
effort: "6d"
dependencies: [2, 3, 5, 6]
---

# Phase 7: Shells

## Goal

The Platform Shell and the Tenant Shell log a user in, learn who they are and which remotes they may use, and mount
those remotes safely. Both are thin applications over one `shell-core` package.

## Boot sequence (in `shell-core`)

1. Load `/console/config.json`; start login with `@suite/auth` (PKCE). Handle the callback and logout routes.
2. `GET /platform/api/v1/me` for scope, tenant and permissions; a user with a scope that does not match the shell
   (a tenant user on the platform host) gets a clear refusal page, not a half-loaded console.
3. `GET /platform/api/v1/console/remotes`; keep it in TanStack Query with refetch on window focus.
4. For each remote: refuse an incompatible `sdkMajor`; verify the manifest with the integrity from the feed through the
   Module Federation runtime `fetch` plugin proven in phase 1 (the covered unit is the manifest; chunks rely on the
   allow-listed same-origin entry path and `script-src 'self'`); register it with the MF runtime; build the navigation
   from `title` and `navOrder`.
5. Route `/console/r/$remote/*` renders the remote through `@suite/shell-sdk` inside an error boundary and Suspense;
   the remote builds its own router on the shell's history (shape A, phase 1); a failing remote shows an error panel
   with a retry, never blanks the shell.

## Screens owned by the shell (design system: see plan.md)

- **Frame**: the inset sidebar layout of the design system. 240 px sidebar (collapsible to 48 px) with a switcher at the
  top, the current remote's sections, the other enabled remotes (`AppMark` tiles from the feed), Access, and the user at
  the bottom; an inset main panel (8 px margin, `radius-xl`) with a 52 px header: sidebar toggle, breadcrumbs, scope
  pill, the Cmd or Ctrl K search button, theme and language menus.
- **Scope**: on the Platform Shell a 2 px `scope-platform` line runs along the top of the inset panel, the header shows a
  "Platform scope" pill with an amber dot and the switcher shows the shield; on the Tenant Shell the switcher shows the
  tenant's initial, name and domain. The switcher is display only: a user belongs to one tenant and the host fixes it.
  The two shells share one theme (light, dark or system); they differ by this scope treatment, title and audience.
- **Command palette** (`CommandPalette`): Cmd or Ctrl K from anywhere. Groups: remotes and their sections (from the feed
  and each remote's registered commands), actions registered by remotes, and record lookup by id pattern through the
  remotes' lookups. `/` focuses the page search, Esc closes the top layer; each shortcut is shown beside its action.
- **Other pages**: home, the "no apps enabled for this tenant" empty state, forbidden, not found, session expired.
- **Language**: the switch sets `ctx.locale` (default from the browser, remembered per viewer in `localStorage` with a
  try/catch and a working default when storage is unavailable); the shell's own text uses its own `i18next` instance.
- No notification bell in v1: the Notification app is not part of the suite.

## Files

- Create: `frontend/packages/shell-core/**`; fill `frontend/apps/platform-shell/**` and `frontend/apps/tenant-shell/**` (entry, index
  HTML, theme choice).
- Modify: `frontend/packages/build-config` if the shell preset needs the registry-driven remote registration.

## Steps

1. Build the boot sequence and the remote host with MSW-backed tests for each feed case: none, one remote, an
   incompatible `sdkMajor`, an integrity mismatch (remote skipped, visible error), a remote that throws on mount, the
   feed request failing (error page with retry, no stale menu), and a user whose token belongs to another tenant than
   the host (`/me` refuses; show the refusal page).
2. Build the frame, scope treatment, navigation and palette from the design system samples (`CommandScreen`,
   `InvoicesScreen`), keyboard accessible, usable from a laptop width down to a phone (sheet full width).
3. Run both shells against the compose stack: log in on `platform.localhost/console/` and on a tenant host, and see the
   feed arrive (it is empty until phase 8 registers `remote-platform`; register a built remote through
   `ops/console/deploy-remote.sh` to see one mount).

## Todo

- [x] Login, callback, logout, expiry handling
- [x] `/me` scope check and refusal page
- [x] Feed query with refocus refetch
- [x] Integrity and `sdkMajor` checks
- [x] Remote host with error boundary
- [x] Inset sidebar frame, switcher, scope pill and line, breadcrumbs
- [x] Command palette with remote commands and record lookups, hotkeys
- [x] Home, empty, forbidden, not found, expired pages
- [x] Language switch, shell catalogs in `en` and `vi`
- [x] Tests for every feed case green

## Verification

```bash
cd frontend && pnpm --filter "@suite/shell-core" test && pnpm turbo run lint typecheck build
```

and a manual login on both hosts against the compose stack, recorded in the phase notes.

## Risks

A shell that reads the feed once per load shows a stale menu after a deploy; refetch on focus bounds that, and the Done
means requires only "after a reload". Token refresh while a remote is mid-request is covered by `getToken()` always
returning a valid token.

## Security

The shell never executes a remote that failed the integrity or compatibility check. It sends the token only to
same-origin API paths. It renders no HTML from the feed (titles are text).

## Result (2026-11-17)

- `shell-core` boots in one order: runtime config, a session (a missing one goes to Keycloak), `/me` with the audience
  check, then the registry feed sorted by `navOrder`. A feed entry is verified before it is mounted: same-origin entry
  path and the registry's `sha384` attached to the manifest request by a Module Federation `fetch` plugin; an
  incompatible `sdkMajor` is refused. The frame renders inside the router, the palette is Cmd or Ctrl K, theme and
  language are remembered, and a remote that fails shows an error panel with Retry.
- `pnpm --filter @suite/shell-core test` covers every feed case, the loader's refusals and the frame; the live check ran
  against the compose stack: both shells signed in on `platform.localhost:8090` and `acme.localhost:8090`,
  `remote-platform` mounted through the registry, and the browser console stayed clean.
- Limits and deviations: the tenant switcher shows the host's label as the tenant name because `/me` returns only the
  tenant id (changing `/me` is a non-goal); the seeded Platform Administrator role predates the new
  `platform:ui-remote:*` codes and system roles are immutable, so the live check's activation needed those two
  role-permission rows inserted directly — a fresh deployment seeds them from the catalog; the console's port is
  configuration (`platform.keycloak.console-port`, 8090 in compose) because Keycloak matches redirect URIs with the
  port; `deploy-remote.sh` now refuses a build whose `console.json` version differs from the version it would publish,
  which caught a dev-base build pointing outside its own directory.
