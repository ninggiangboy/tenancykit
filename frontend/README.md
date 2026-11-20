# Suite Console

The console for the suite: a pnpm + Turborepo monorepo with two shells and every UI as a Module Federation remote. A
Platform operator works at `platform.<base>/console/`; a Tenant's people work at `<tenant>.<base>/console/`. Both shells
are thin applications over `@suite/shell-core`, and an app's screens ship on their own as a remote: deploying
`remote-billing` never rebuilds or redeploys a shell.

The plan that defines this directory, with the acceptance criteria, lives in
[`plans/261117-1456-suite-console`](../plans/261117-1456-suite-console/plan.md).

## Layout

```
apps/platform-shell    Platform Shell, served on the platform host
apps/tenant-shell      Tenant Shell, served on every tenant host
remotes/platform       tenants, users, roles, tenant systems, the UI remote registry
remotes/billing        money flow, pricing and coupons, wallets and usage, webhooks and Stripe
packages/build-config  the one Vite + federation preset for a shell and for a remote
packages/shell-core    the boot sequence, the frame and the remote host used by both shells
packages/shell-sdk     the contract between a shell and a remote: ShellContext, RemoteModule, SDK_MAJOR
packages/auth          oidc-client-ts against Keycloak; imported by the shells only
packages/ui            the Suite Console design system, ported to React 19 and Tailwind 4
packages/remote-kit    what every remote shares: runtime provider, list and sheet kit, dialogs, forms, router
packages/api-client    the generated client for contracts/openapi, list-query helpers, error type
e2e                    Playwright against the compose stack
```

## Requirements

Node 24 (`.nvmrc`), pnpm 12.21 (the `packageManager` field; Corepack or `npm i -g pnpm@12.21.0`). No other tool is
needed for the frontend; the backends and the proxy come from `docker compose` at the repository root.

## Commands

Run them from `frontend/`.

```bash
pnpm install --frozen-lockfile     # the lockfile is committed
pnpm dev                           # every shell and remote dev server
pnpm lint                          # Biome: format and lint, no writes
pnpm lint:fix                      # Biome with writes
pnpm typecheck                     # tsc --noEmit in every package
pnpm test                          # Vitest in every package
pnpm build                         # production build of every shell and remote
pnpm turbo run lint typecheck test build   # what CI runs
```

A single package, for example the shells' shared host:

```bash
pnpm --filter @suite/shell-core test
pnpm --filter @suite/remote-billing build
```

Ports: Platform Shell 5173, remote-platform 5174, Tenant Shell 5175, remote-billing 5176. All are `strictPort`, so a
stale process fails loudly instead of being shadowed.

## How a remote is organised

A remote is a table of screens plus one folder per feature. `src/routes.tsx` lists each screen once (path, nav section,
permission, component) and `@suite/remote-kit` builds the router, the permission checks and the navigation the shell
shows in its sidebar from it. A feature folder never holds logic in `page.tsx` alone:

```
features/<name>/
  page.tsx          composition only: spec, list, sheet, dialogs
  list-spec.ts      search, facets, views and sortable columns
  columns.tsx       the table's columns
  schema.ts         Zod schemas of the forms (messages are kit keys)
  hooks/            one hook per query or write; permissions in one place
  components/       the sheet, the dialogs and any other piece of the screen
```

Forms are React Hook Form with Zod (`useZodForm`, `FormField`, `FormEntityPicker`). A form never asks for a record's raw
ID: it uses a picker (`useCustomerOptions`, `useWalletOptions`, `usePlanOptions` in `src/components/lookups.tsx`).
Amounts are typed in the currency's own units and converted to minor units in `src/components/money.ts`.

Tailwind does not scan another package, so a remote's `index.css` names `remote-kit` with `@source` and a shell's names
`shell-core`; a utility used only inside a shared package is missing without it.

## Adding a remote

1. Copy `remotes/billing` to `remotes/<name>`, change `name` in `package.json` and in `vite.config.ts`, and keep
   `src/app.tsx` exporting the module through `defineRemote`: a `Root` that takes the shell's context.
2. Build with `REMOTE_VERSION=<version> pnpm --filter @suite/remote-<name> build`. The build's asset URLs already point
   at `/console/remotes/<name>/<version>/`, which is the only entry path the registry accepts.
3. Publish and register the version with `ops/console/deploy-remote.sh <name> <dist-dir>`; an operator then activates it
   in Platform Admin. Regulating what the browser runs is the registry's job, so nothing here needs to know the URL.

## Rules that hold across packages

- A remote imports `@suite/shell-sdk` and `@suite/ui`, never a shell, `@suite/auth` or another remote.
- Tokens live in the shell's memory. A remote asks `ctx.getToken()`; nothing writes a token to storage.
- `@suite/ui` is not a shared runtime singleton: each remote bundles what it imports. The token CSS variables are
  defined once by the shell, and remotes ship utilities only.
- `react`, `react-dom`, `@tanstack/react-query` and `@tanstack/react-router` are singletons with the version range owned
  by `@suite/build-config`; change the range there, never in a package.
