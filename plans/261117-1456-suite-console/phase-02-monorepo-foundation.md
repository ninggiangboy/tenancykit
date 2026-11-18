---
title: "Phase 2: Monorepo foundation"
status: completed
priority: P1
effort: "1.5d"
dependencies: [1]
---

# Phase 2: Monorepo foundation

## Goal

`frontend/` becomes a real pnpm + Turborepo workspace whose empty apps, remotes and packages build, lint, typecheck and test
from a clean clone, with a CI workflow that runs the same commands.

## Context

- Decisions from [phase 1](./phase-01-federation-spike.md) fix the bundler plugin and the TypeScript version:
  Vite 8 with `@module-federation/vite` 1.24.1, and TypeScript 7.0.2 (native `tsc`, `--noEmit` only) with 6.0.3 as the
  documented fallback. The working spike presets move into `packages/build-config`.
- Repo convention: one workflow per service under `.github/workflows/`, path-filtered; generated code is committed and
  compared in CI.

## Layout

```text
frontend/
  package.json  pnpm-workspace.yaml  turbo.json  biome.json  tsconfig.base.json  .nvmrc  README.md
  apps/platform-shell   apps/tenant-shell
  remotes/platform      remotes/billing
  packages/build-config  shell-core  shell-sdk  auth  ui  api-client
  e2e/
```

`build-config` holds the Vite (or Rsbuild) presets so every shell and remote declares federation the same way.
`shell-core` is the shared host implementation; the two shell apps stay thin (filled in phase 7).

## Files

- Create: everything under `frontend/` above; `.github/workflows/frontend.yml`.
- Modify: `frontend/README.md` (the renamed placeholder, rewritten with how to run, test and add a remote), `.gitignore` (node_modules,
  dist, turbo cache).
- Delete: `frontend/spike/` after its working parts are folded into `build-config`.

## Steps

0. The `web/` placeholder was renamed to `frontend/` in phase 1; confirm `web/` no longer exists. Every path below is
   under `frontend/`.
1. Create the root `package.json` (`private`, `packageManager: pnpm@12.21.0`, `engines.node >=24`), `pnpm-workspace.yaml`
   (`apps/*`, `remotes/*`, `packages/*`, `e2e`), `.nvmrc` with `24`.
2. Add `turbo.json` with `lint`, `typecheck`, `test`, `build` tasks and correct `dependsOn` and `outputs`.
3. Add `biome.json` (formatter and linter, one config for all packages; the generated client under
   `packages/api-client/src/generated` is excluded, as measured in phase 1) and `tsconfig.base.json` (strict,
   `noUncheckedIndexedAccess`, `moduleResolution: bundler`).
4. Add `packages/build-config` exporting the shell and remote presets from the phase 1 result (shared singletons:
   `react`, `react-dom`, `@tanstack/react-query`, `@tanstack/react-router`, strict version range; `@suite/ui` is
   **not** shared).
5. Create each package, app and remote as a minimal buildable unit with its own `package.json`, `tsconfig.json` and a
   passing Vitest smoke test; remotes expose a `./app` module (the contract lands in phase 6).
6. Add `.github/workflows/frontend.yml`: path filter `frontend/**`, `contracts/openapi/**`, the workflow itself; Node 24, pnpm
   from `packageManager`, `pnpm install --frozen-lockfile`, `biome ci`, then `pnpm turbo run typecheck test build`.
   The generated-client drift step is added in phase 6 once the generator exists.
7. Delete `frontend/spike/`.

## Todo

- [x] Workspace and Turborepo configured, lockfile committed
- [x] Biome and TypeScript base config
- [x] `build-config` presets
- [x] All apps, remotes and packages build
- [x] `frontend.yml` workflow
- [x] README replaced, spike removed

## Verification

```bash
cd frontend && pnpm install --frozen-lockfile && pnpm biome ci . && pnpm turbo run typecheck test build
```

Exit code 0, and the same on a second run is a full Turborepo cache hit.

## Risks

Version ranges for the shared singletons: too loose and two remotes bring two React copies; too strict and an
independent deploy is blocked. Start with one range owned by `build-config` and change it in one place.

## Security

No secret in the workflow; `pnpm install` runs with the lockfile frozen.
