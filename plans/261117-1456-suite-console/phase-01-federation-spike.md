---
title: "Phase 1: Federation spike"
status: completed
priority: P1
effort: "2d"
dependencies: []
---

# Phase 1: Federation spike

## Goal

Prove on real tooling, before any screen is written, that a shell can load a remote whose URL is unknown at build time,
that TanStack Router composes across that boundary, and that the chosen toolchain builds the generated API client.
The phase ends with a written decision for each open technical question.

## Context

- Plan: [plan.md](./plan.md), stack and risk register.
- Owner decision: TanStack Router (`docs/architecture.md` row "Frontend"). It is **not** swapped here; if the router
  cannot compose, the phase stops and the user decides.
- Keycloak client `suite-console` (public, PKCE) exists in `ops/keycloak/realm-export.json`.

## Files

- Create (scratch, deleted in phase 2 after folding the winners in): `frontend/spike/shell`, `frontend/spike/remote`,
  `frontend/spike/package.json`.
- Create: `plans/261117-1456-suite-console/reports/spike-federation.md` (the decision record).
- Modify: `plans/261117-1456-suite-console/plan.md` (stack table and risk register) with the decisions.

## Questions and pass criteria

1. **Bundler.** Vite 8 with `@module-federation/vite` 1.24: the shell loads a remote through the MF2 runtime
   (`registerRemotes` and `loadRemote`) from a manifest URL read at runtime, sharing `react`, `react-dom`,
   `@tanstack/react-query` and `@tanstack/react-router` as singletons. Pass: remote renders, one React instance
   (`React` identity checked in a test), production build, not only dev. Fail: use Rsbuild 2 with
   `@module-federation/rsbuild-plugin` and repeat.
2. **Router composition.** Shape A: the shell has a splat route and the remote creates its own router with `basepath`
   on the shared browser history. Shape B: the shell owns the router and the remote exports a function that adds its
   routes. Pass for a shape: deep link `/console/r/hello/items/42` survives a reload; back and forward work across the
   shell and the remote; the shell does not re-render on a remote navigation; the shell's active menu item follows the
   URL. Record the winner. If neither passes: stop and report to the user.
3. **Integrity.** The shell fetches the manifest with `fetch(url, { integrity })` before registering the remote.
   Measure what that covers: record whether chunk URLs listed in the manifest can also be verified. If not, the control
   is the same-origin entry path plus a CSP with `script-src 'self'`; write that limit down.
4. **Independent deploy.** Build remote version 2 into a new versioned directory, change only the registry stub, and
   the unchanged shell build loads version 2 after a reload while a tab still on version 1 keeps working (immutable
   assets).
5. **Toolchain.** `tsc` 7.0.2, Biome 2 and `@hey-api/openapi-ts` 0.99 run on `contracts/openapi/platform.yaml` and
   `contracts/openapi/billing.yaml`: generation succeeds, the flat `filter[...]` parameter names become typed query
   keys, and the output typechecks. Fail on TypeScript 7: pin 6.x and record why.
6. **Auth hand-off.** The shell logs in with `oidc-client-ts` against `suite-console` and passes a token getter to the
   remote through a context object; the remote calls `GET /platform/api/v1/me` with it.

## Steps

0. Rename the placeholder directory first with `git mv web frontend` (it holds only `README.md`), because the spike lives
   under `frontend/`.
1. Run Keycloak, Platform and Caddy from `docker-compose.yml` (reuse a running stack; check `docker compose ps` first).
2. Build `frontend/spike/*` with fixed ports (shell 5173, remote 5174) so a stale process is stopped, not duplicated.
3. Answer questions 1 to 6 in order; for each keep the command and the observed output in the report.
4. Stop every dev server started here (`lsof -i :5173`, `lsof -i :5174`) before ending the phase.
5. Write `reports/spike-federation.md`: one table of question, result, evidence, decision. Fold the decisions into
   `plan.md` (stack table, risk register) and into any phase text they change.

## Todo

- [x] Bundler question answered on a production build
- [x] Router composition shape chosen, or the user is asked and the plan paused
- [x] Integrity limit recorded
- [x] Independent-deploy check passed
- [x] Toolchain question answered, TypeScript version pinned
- [x] Auth hand-off works against Keycloak
- [x] Decision report written, plan updated, dev servers stopped

## Success criteria

- Each of the six questions has a pass or fail with a command and its output in the report.
- `plan.md` names the bundler, the router shape, the integrity mechanism and the TypeScript version.
- No listener remains on ports 5173 and 5174.

## Risks

Rolldown behaviour differs from Rollup at the edges the federation plugin touches; the fallback keeps the application
code identical because both bundlers emit the same MF2 manifest format.

## Security

The spike uses the dev realm only; no secret is written into the report.
