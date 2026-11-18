---
title: "Phase 6: Shared packages"
status: completed
priority: P1
effort: "10d"
dependencies: [2]
---

# Phase 6: Shared packages

## Goal

Four packages every shell and remote builds on: `shell-sdk` (the contract between a shell and a remote), `auth`,
`api-client` (generated from `contracts/openapi`), and `ui` (the Suite Console design system ported to React 19 and
Tailwind 4; see the Design system section of [plan.md](./plan.md)).

## Packages

**`@suite/shell-sdk`**
- `ShellContext`: `getToken()`, `user` (id, email, scope, tenantId, permissions from `/me`), `tenant` (id, code, name),
  `basepath`, `navigate(to)`, `theme`, `locale` (`en` or `vi`) with a subscription to changes, and `commands`: a remote
  registers command-palette items (label, keywords, shortcut, `onSelect`) and optional record lookups (an id pattern such
  as `INV-...` plus a function that resolves the record), which the shell shows in its palette.
- `RemoteModule`: `{ name, sdkMajor, Root(props: { ctx }) }`, `defineRemote()`, `SDK_MAJOR`, `isCompatible(sdkMajor)`,
  hooks `useShell()` and `usePermission(code)`. Shape follows the phase 1 decision: the shell passes `history` and
  `basepath` in `ShellContext` and mounts `Root` from a splat route, so the remote creates its own router on the shell's
  history (shape A). `ctx.integrity` is not part of the contract; the shell alone verifies manifests.
- Remotes never import from each other; this package is the only seam.

**`@suite/auth`**
- `oidc-client-ts` `UserManager` configured from the runtime `/console/config.json`: authority code flow with PKCE,
  tokens held in memory (`InMemoryWebStorage`), refresh through the refresh token, redirect handling and logout. Only the
  shells import it; a remote receives `getToken` through the context.

**`@suite/api-client`**
- `openapi-ts.config.ts` per app reading `contracts/openapi/platform.yaml` and `contracts/openapi/billing.yaml`; plugins
  for types, SDK, TanStack Query hooks and Zod; output committed under `src/generated/{platform,billing}`.
- `createApiClients(ctx)`: base URLs (`/platform` for Platform, root for Billing, matching Caddy), `Authorization` from
  `getToken`, `X-Request-Id`, `Idempotency-Key` on every mutation that declares it (12 parameters in `billing.yaml`),
  `If-Match` from the entity `version` wherever an operation declares it (3 in `billing.yaml`, 8 in `platform.yaml`).
- `listQuery({ filters, or, sort, limit, cursor | offset, pageInfo })`: builds the flat keys of the list-query contract
  (`filter[created_at][eq]`, `filter[or][0][total][gte]`, `sort=-total`, never both `offset` and `cursor`); tested with the
  examples in `docs/list-query-contract.md`.
- `permissions.generated.ts`: operation id to the `x-required-permission` of each operation, generated from the OpenAPI.
- Error type: read how `core` renders an error body before defining `ApiError`; do not guess the shape.

**`@suite/ui`** (the design system, ported; the artifact's `window.Suite` bundle is React 18 plain JavaScript and is not used)
- Source of truth: the snapshot in `plans/261117-1456-suite-console/reports/design-system/` (`tokens.json`, the README,
  `components-index.d.ts`), pinned to artifact version `1794921831-f0e0`. Copy `tokens.json` to
  `frontend/packages/ui/design/tokens.json` and generate `src/styles/tokens.css` from it with a script
  (`pnpm --filter @suite/ui tokens:build`): CSS variables under `:root` and `.dark` using the token names as shadcn
  does (`--background`, `--foreground`, `--card`, `--popover`, `--muted`, `--accent`, `--border`, `--input`, `--ring`,
  `--primary`, `--sidebar-*`, `--chart-1..5`) plus the extra tokens `brand`, `selection`, `ring-halo`, `success`,
  `warning`, `destructive`, `info`, `overlay`, `scope-platform`, the radius, shadow, size and timing tokens, mapped into
  Tailwind 4 with `@theme inline`. Light and dark only: the `platform` and `tenant` themes of the first draft are
  dropped; scope is shown by the `scope-platform` line and pill (phase 7). A test compares the generated variables with
  `tokens.json`.
- Fonts: Inter (variable), Be Vietnam Pro as the Vietnamese fallback in the stack, Geist Mono for codes only, all
  self-hosted with Fontsource (`@fontsource-variable/inter`, `@fontsource/be-vietnam-pro`,
  `@fontsource-variable/geist-mono`) and loaded by the shells, so the CSP needs no external font host. Type scale
  classes `text-metric`, `text-title`, `text-heading`, `text-body`, `text-body-strong`, `text-small`, `text-caption`,
  `text-code` from the token file.
- Icons: `lucide-react`, with the design system's sizes (14, 16, 18) and `aria-label` plus `Tooltip` on icon-only buttons.
- Components, API shaped like `components-index.d.ts` so the screens in the design artifact translate directly:
  `Button` (variants default, brand, secondary, outline, ghost, destructive, link; `loading`, `shortcut`), `Field`,
  `Checkbox`, `Switch` (`busy`), `StatusBadge` (dot plus word; a mapping from Billing and Platform enums to tones),
  `AppMark`, `Money` (integer minor units, the currency's own decimals, always with the currency code), `CopyId`,
  `Kbd`, `Tabs` (segmented and line), `Card`, `Stat`, `Alert`, `FilterChip` and `FacetedFilter` (one applied predicate and
  one field's values of the list query contract, with the wire syntax in a tooltip), `DataTable` (TanStack Table 9,
  server-side sort, filter and paging, offset and cursor, selection, row actions, density, keyboard j/k, arrows, Enter,
  x), `BulkBar`, `DropdownMenu`, `Tooltip`, `Dialog` (with an impact list), `Sheet` (record peek with previous and next),
  `Toaster` with `useToasts` (Sonner underneath, Undo action), `CommandPalette` with `useHotkey`, `Skeleton`
  (shown only after 150 ms), `EmptyState`, `ErrorState`, `UnsavedBar` (floating bar with change count, Discard, Save
  with Cmd or Ctrl S, modelled on the `RoleScreen` sample), `PermissionGate`, the Recharts wrapper using `chart-1..5`.
  Built on the shadcn CLI 4 primitives (Radix, `cmdk`) restyled with the tokens; controls 32 px, toolbar 28 px, table
  rows 44 px (36 px compact).
- URL state: `DataTable`, `Tabs`, `FacetedFilter` and `Sheet` read and write TanStack Router search params through a
  small adapter, so tabs, filters, sort, page size and the open record are in the URL.
- Language: `ui` imports no i18n library. It exposes a `UiMessagesProvider` with built-in `en` and `vi` catalogs for its
  own strings; `Money`, number and date formatters use `Intl` with the context locale; times render in UTC and the column
  header says "(UTC)". Each remote owns the rest of its text (see phase 8 onwards), written in sentence case with a verb
  and an object on buttons.
- Not a shared runtime singleton: each remote bundles what it imports. Token variables are defined by the shell; a
  remote ships utilities only. Overlay layers use one documented z-index scale so a remote's `Dialog` stacks correctly
  over the shell's `Sheet` and palette.

## Files

- Create: `frontend/packages/{shell-sdk,auth,api-client,ui}/**`, `frontend/packages/api-client/openapi-ts.config.ts`.
- Modify: `.github/workflows/frontend.yml` (add `pnpm --filter @suite/api-client generate` then `git diff --exit-code`).

## Steps

1. `shell-sdk` types and `defineRemote` first, since the shells, remotes and tests all import them.
2. `api-client`: configure generation, commit the output, write `listQuery` against the documented examples, write the
   permission map generator, add the drift check to CI.
3. `auth`: wrapper plus a test with a stubbed `UserManager`.
4. `ui`: copy `tokens.json`, write the token build script and its test, load the fonts; then primitives with both
   catalogs for every built-in string; then `DataTable`, `FacetedFilter`, `FilterChip`, `Sheet`, `Dialog` and `Toaster`
   (the most reused); then `CommandPalette`, `UnsavedBar`, charts and the rest. Check each component against its
   `project/components/<Name>/preview.html` in the design artifact (read it with the Artifact tool) in light and dark.
5. Component tests with Testing Library and MSW; a `DataTable` test drives the real `listQuery` helper.

## Todo

- [x] `shell-sdk` contract and compatibility check
- [x] `api-client` generated, `listQuery` tested, permission map, drift check in CI
- [x] `auth` wrapper and test
- [x] `ui` tokens from `tokens.json` with a matching test, self-hosted fonts
- [x] `ui` primitives, `DataTable`, `FacetedFilter`, `FilterChip`, `Sheet`, `Dialog`, `Toaster`, `Skeleton`, states
- [x] `CommandPalette`, `UnsavedBar`, `StatusBadge` enum mapping, `Money`, charts
- [x] `en` and `vi` built-in catalogs, URL-state adapter
- [x] Biome, typecheck, tests green

## Verification

```bash
cd frontend && pnpm turbo run lint typecheck test build && pnpm --filter @suite/api-client generate && git diff --exit-code frontend/packages/api-client
```

## Risks

The design artifact can change after this snapshot; diff its version id against `1794921831-f0e0` before syncing and
never fetch it at build time. A generated client that mirrors an unstable OpenAPI churns often; the drift check turns a contract change into a visible
diff. If hey-api mishandles the bracketed parameter names, `listQuery` still produces the keys and the generated type
is used only for the response.

## Security

Tokens live in memory, not `localStorage`, so a script injected into a remote cannot read a stored token (it can still
call the context; the CSP and the registry controls are the guard). No secret in generated code.

## Result (2026-11-17)

- `@suite/api-client` generates from both committed documents (114 operations with an `x-required-permission`), commits
  the output under `src/generated`, builds the flat list-query keys (`listQuery`, tested against the examples in
  `docs/list-query-contract.md`), emits `permissions.ts` from the OpenAPI extension, and adds the drift check to
  `frontend.yml` (`pnpm --filter @suite/api-client generate && git diff --exit-code`).
- `@suite/auth` holds tokens in memory, refreshes before expiry and keeps its methods bound, because the shell hands
  `getToken` to the clients as a plain function.
- `@suite/shell-sdk` gains `ShellProvider`, `useShell`, `usePermission`, `can` and `useRemoteCommands` on a
  `./react` subpath, so the root entry stays free of JSX and a Vite config can import `SDK_MAJOR`.
- `@suite/ui` ports the design system to React 19 and Tailwind 4: `tokens.css` is generated from the pinned
  `tokens.json` and a test compares every colour variable in light and dark; the components listed in the phase, the
  `en`/`vi` catalogs with a key-parity test, and the URL-state adapter are in place.
- Deviations recorded: `@hey-api/openapi-ts` 0.99 needs the TypeScript 6 compiler API, so `@suite/api-client` pins
  `typescript` 6.0.3 while the rest of the monorepo typechecks with 7.0.2; a remote imports the same `theme.css` entry
  as a shell (tokens and utilities together) rather than a utilities-only entry; `DataTable` is server-driven and does
  not use TanStack Table's column model yet, though the dependency is declared for the screens that need client-side
  columns.
