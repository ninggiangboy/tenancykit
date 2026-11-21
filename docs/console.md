# Suite Console

The console is the suite's one user interface: a pnpm + Turborepo monorepo in `frontend/`, two shells split by
audience, and every app's UI shipped as an independently deployable Module Federation remote. A Platform operator works
at `platform.<base>/console/`; a Tenant's people work at `<tenant-code>.<base>/console/`. Deploying an app's UI never
rebuilds or redeploys a shell.

The plan that defines it, with the acceptance criteria, is
[`plans/261117-1456-suite-console`](../plans/261117-1456-suite-console/plan.md).

## Layout

```
frontend/
  apps/platform-shell      the Platform Shell, served on the platform host
  apps/tenant-shell        the Tenant Shell, served on every tenant host
  remotes/platform         tenants, users, roles, tenant systems, the UI remote registry
  remotes/billing          money flow, pricing and coupons, wallets and usage, webhooks and Stripe
  packages/build-config    the one Vite + federation preset for a shell and for a remote
  packages/shell-core      the boot sequence, the frame and the remote host used by both shells
  packages/shell-sdk       the contract between a shell and a remote: ShellContext, RemoteModule, SDK_MAJOR
  packages/auth            oidc-client-ts against Keycloak; imported by the shells only
  packages/ui              the Suite Console design system, ported to React 19 and Tailwind 4
  packages/remote-kit      the runtime provider, list and sheet kit, dialogs, forms and router every remote shares
  packages/api-client      the generated client for contracts/openapi, list-query helpers, error type
  e2e                      Playwright against the compose stack
```

Both shells are built from `shell-core`; they differ in one `definition` (audience and title). A remote exports
`Root({ ctx })` through `defineRemote` and builds its own TanStack Router on the history and basepath the shell hands
it, so a navigation inside a remote never re-renders the shell.

A remote also hands the shell its screens through `ctx.registerNavigation(sections)`, already filtered by the caller's
permissions. The shell lists them under the app in its sidebar and uses the current one in the breadcrumb. The field is
optional on both sides, so the SDK major stays 1: a shell without it leaves the remote to render its own section bar,
and a remote that never registers shows no sections in the sidebar. `@suite/remote-kit` does the registering for every
remote built on its router.

## The `/console/` prefix

Caddy routes `/console/*` to the console's static server (`ops/console/`) on every host; the hostname decides which
shell that server answers with. Adding the console therefore moved no existing route: Platform stays at the root of
`platform.localhost`, every unmatched path still reaches Billing, and each app's API keeps its prefix. Shell routes
live under `/console/`, and a remote is mounted at `/console/r/<name>/...`.

## Runtime flow

1. The shell reads `/console/config.json`, rendered from the environment by the static server's entrypoint: the
   Keycloak authority, the public client id and the two API prefixes. Nothing in it is secret.
2. Without a session it sends the browser to Keycloak (PKCE, public client `suite-console`); the callback brings the
   session back. Tokens live in the shell's memory only, so a reload signs in again through Keycloak's own session.
3. `GET /platform/api/v1/me` says who the caller is and which permission codes they hold; a caller from the other
   audience is refused with a page instead of a half-loaded console.
4. `GET /platform/api/v1/console/remotes` returns only the remotes the caller may use: enabled, for their audience,
   and — for a Tenant — backed by a tenant entitlement. The sidebar is that list.
5. Opening one mounts it: the shell registers the entry and a runtime `fetch` plugin attaches the registry's `sha384`
   integrity to the manifest request, so the bytes verified are the bytes parsed. A remote built for another
   `shell-sdk` major is refused with an explanation rather than mounted.

Only the manifest is covered by the hash. Chunk bodies are guarded by three other things: the entry must be on the
console's own origin and under `/console/remotes/<name>/<version>/`, a version directory is immutable once published,
and the static server's CSP is `script-src 'self'`.

## The design system

The console follows the Suite Console design system, **v4** (artifact version `1795165481-3dca`, tokens
`version` 4), the project owner's artifact. A snapshot of the earlier version's `README.md`, `tokens.json` and
component API is kept in `plans/261117-1456-suite-console/reports/design-system/`; the full v4 copy the console was
ported from is in `plans/261120-1329-console-ds-v4/reference/design-system-v4/`.

It is ported, not bundled: the artifact ships a React 18 plain-JavaScript bundle, while the console is React 19,
TypeScript and federated. `@suite/ui` keeps the token names, values, component names and props and rebuilds the
components on shadcn primitives. The token names already match shadcn's CSS variables one to one, which is why the
tokens drop into `globals.css` unchanged. `packages/ui/src/tokens.test.ts` fails when the generated CSS stops matching
`tokens.json`.

To sync a new version of the artifact: copy the new `README.md`, `tokens.json` and `index.d.ts` into the snapshot
directory, regenerate `packages/ui/src/styles/tokens.css` from `tokens.json`, run the token test and the component
tests, and review the visual changes in the artifact's previews in light and dark.

The rules every screen follows — keyboard first, peek instead of navigate, undo over confirm, optimistic switches,
never a dead end, the unsaved bar, copyable identifiers, money in minor units, UTC times, state in the URL, sentence
case, one accent per app, two densities, and the phone and tablet layouts — come from the design system and are the
acceptance criteria of the plan. `@suite/ui` is not a shared runtime singleton: each remote bundles what it imports,
and the shell owns the CSS variables.

The shell writes `data-app` and `data-density` on `<html>`, because Radix portals sheets, dialogs and menus into
`<body>`, outside the frame; an attribute or variable set on the frame's `div` would not reach them. The app-scope
block in `packages/ui/src/styles/theme.css` re-declares the accent aliases (`--primary` and the others) inside
`[data-app]` because a custom property resolves where it is declared, so setting `--app-accent` alone changes nothing
below it. Density swaps the control, row and page sizes through their variables; the phone breakpoint raises every
control to the touch target.

Two rules keep sizes honest. The document's root font is 14px, so `theme.css` sets Tailwind's `--spacing` to `4px`:
without it `h-11` would be 38.5px and every touch target a little short. A control's height still comes from
`--size-control-sm`, `--size-control-md` or `--size-touch-target`, not from a spacing class. And `cn()` must know every
`text-<name>` size `theme.css` defines (`TYPE_SCALE`; a test compares the two), or tailwind-merge reads the size as a
colour and drops one of them.

Motion follows the design system's three durations. Overlays enter on `ease-out` and leave on `ease-in`: `theme.css`
defines the keyframes as `animate-*` utilities (`fade`, `pop`, `zoom`, `slide-in-right`, `slide-in-up`, `drop`, `rise`)
and a component picks them with Radix's `data-[state=open|closed]`; the leave animations keep their last frame so Radix
holds the part mounted until they finish. `motion-colors` is the hover, press and focus transition. A bottom sheet
follows the finger while dragged, the scrim fades with it, and a release that closes carries it off screen before the
caller's `onClose` runs. Data never animates, and reduced motion zeroes every duration.

## The UI remote registry

Platform owns the registry: `ui_remotes` (name, app code, title, navigation order, audiences, enabled flag and active
version) and `ui_remote_versions` (an immutable version and its manifest path, hash and SDK major).

| Endpoint | Permission | Who calls it |
|---|---|---|
| `GET /api/v1/ui-remotes` | `platform:ui-remote:read` | the registry screen |
| `PUT /api/v1/ui-remotes/{name}` | `platform:ui-remote:manage` | an operator: enable, disable, activate a version |
| `POST /api/v1/ui-remotes/{name}/versions` | `platform:ui-remote:register` | a remote's CI, through the `frontend-ci` service client |
| `GET /api/v1/console/remotes` | any authenticated caller | the shells, filtered on the server |
| `GET /api/v1/tenants/{tenantId}/apps` | `platform:tenant:read` | the tenants screen and the entitlement switch |

Registering is not deploying: a version registered by CI is inert until an operator activates it. Activation is the
operator's one click, and pointing a remote back at an older registered version is a rollback that needs no build.

The entry path is allow-listed to `/console/remotes/<name>/<version>/`, and the registry refuses anything else with
`400`; the hash is stored with the version and verified by the shell. The `frontend-ci` service client holds exactly
`platform:ui-remote:register`, so a leaked CI secret can register a version but cannot read a tenant or enable a
remote. Its secret is `FRONTEND_CI_SERVICE_CLIENT_SECRET` and Platform stores only its SHA-256 in
`PLATFORM_FRONTEND_CI_SERVICE_CLIENT_SECRET_HASH`; rotate both together.

## Deploying

The console's static server (`ops/console/`) serves one volume: `shells/platform`, `shells/tenant` and
`remotes/<name>/<version>`. Two scripts write it.

```bash
# Build the version a remote publishes. The asset URLs carry the version, so the build and the publish must agree.
REMOTE_VERSION="0.0.0+$(git rev-parse --short HEAD)" pnpm --filter @suite/remote-billing build

# Copy it to an immutable directory and register the version. An operator then activates it.
# The publish needs FRONTEND_CI_SERVICE_CLIENT_SECRET in the environment (and, for a copy already published with
# other bytes, a new version rather than a rewrite).
ops/console/deploy-remote.sh billing frontend/remotes/billing/dist

# Stage a shell build; the swap is a rename, so a reload never sees half a build.
ops/console/deploy-shell.sh tenant frontend/apps/tenant-shell/dist
```

Each remote has its own workflow (`frontend.yml` verifies everything, `remote-billing.yml` and `remote-platform.yml`
add a deploy job). Locally, `make console` builds the console and publishes the shells and both remotes, and prints
what the operator still has to activate.

## Adding a remote

The extension path is the same for every app:

1. Copy `frontend/remotes/billing` to `frontend/remotes/<name>`; set `name` in `package.json`, in `vite.config.ts`
   and in the `console` block (`appCode`, `title`, `audiences`, `navOrder`).
2. Build with `REMOTE_VERSION=<version>`.
3. Publish and register with `ops/console/deploy-remote.sh <name> <dist>`; activate the version in Platform Admin.
4. Add the workflow, and the tenant entitlement the app already has.

The shells, `shell-sdk`, the registry API and the deploy script do not change. The end-to-end suite proves this by
building a throwaway second remote from the template, registering and activating it, asserting it appears for its
audience, and removing it again.

## Running and testing

```bash
cd frontend
pnpm install --frozen-lockfile
pnpm dev                       # shells and remotes with hot reload
pnpm turbo run lint typecheck test build
```

Ports: Platform Shell 5173, remote-platform 5174, Tenant Shell 5175, remote-billing 5176; all `strictPort`.

A shell's dev server proxies `/platform`, `/console/config.json` and `/console/remotes` to the gateway on `:8090`
(`make up` or `make gateway`; `shellConfig` in `frontend/packages/build-config` owns the list). Vite cannot answer the
last two: the runtime config is rendered by the static server's entrypoint, and the registry hands the shell manifest
URLs of published remote versions that only the static server holds. Without the proxy Vite returns `index.html` for
both and the shell fails on a JSON parse error before it paints.

The end-to-end suite (`frontend/e2e`) drives the deployed console through Caddy, never a dev server. It needs the
compose stack up, the two shells staged and both remotes published, and it reads the same `.env` the compose file uses
(or the environment CI passes):

```bash
docker compose up -d --build --wait
ops/console/deploy-shell.sh platform frontend/apps/platform-shell/dist
ops/console/deploy-shell.sh tenant frontend/apps/tenant-shell/dist
REMOTE_VERSION_OVERRIDE="0.0.0+$(git rev-parse --short HEAD)" ops/console/deploy-remote.sh billing frontend/remotes/billing/dist
pnpm --dir frontend --filter @suite/e2e test
```

Each test provisions its own tenant with its own host, through the Platform API, and never uses a committed password.
The suite proves: an independent deploy with no shell change, the entitlement removing a menu, the operator disabling
a remote and rolling back, every screen against the real backends, a missing permission (control absent, `403` from
the API), a rejected entry path, the Vietnamese catalogs, the keyboard and URL rules, the token values, stacking and
accessibility, and the extension trace. `10-visual.spec.ts` captures every Platform and Billing screen and both homes
in twelve combinations (1280, 900 and 390 wide; light and dark; comfortable and compact), checks that no phone screen
scrolls sideways and runs axe for contrast and target size; `11-mobile.spec.ts` walks the phone flows: the drawer, the
card list, a bottom sheet dragged closed, Back, the floating action and the density switch. `12-screen-audit.spec.ts`
walks every screen in five looks, opens what each one opens (the record sheet and its buttons, the page actions, the
filter popovers, the tabs, the row menu) and forces a slow and a failing response on each list. It measures control
heights, tap targets, input size on phones, a second highlighted nav item, a fixed button over content, motion on every
overlay, focus return, and the amount, id and message-key rules, and writes `report.md` and `findings.json` next to its
captures in `frontend/e2e/test-results/audit/` (set `AUDIT_DIR` to put them elsewhere). It fails on any finding, so a
new screen or component that ships a small target, a silent overlay or a second highlighted nav item breaks the build;
`AUDIT_STRICT=0` collects the report without failing. CI runs the suite in the `suite`
workflow on the same stack as the suite smoke.

## Limits

- v1 has one real remote per shell (`remote-platform` is mounted in both), so multi-remote composition on one screen
  is not proven; the extension trace proves a second remote loads and can be removed.
- A list screen shows `/`, j and k, Enter, x and Esc where it has a search field; `payments` has no text column to
  search, so its toolbar has no search box.
- API error messages stay in the language the API returns; the screens translate only their own text.
- The manifest is hash-verified; chunk bodies rely on the same-origin entry path, immutable version directories and
  the CSP, as recorded in the plan.
- Production asset publishing (S3 or CloudFront) and the ECS deployment of `frontend/` are out of scope; the compose
  stack's static server is the runtime this document describes.
