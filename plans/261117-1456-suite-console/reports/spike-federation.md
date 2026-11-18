# Federation spike — decision record

Run on 2026-11-17 against the compose stack already running (`keycloak` on `:8081`, `platform` on `:8082`,
`caddy` on `:8090`). The spike lives in `frontend/spike` and is deleted in phase 2, after its working parts move into
`packages/build-config`.

Tooling observed: Node 26.10.0, pnpm 12.8.1, Vite 8.0 (`rolldown`), `@module-federation/vite` 1.24.1,
`@module-federation/runtime` 2.x, React 19.3.1, `@tanstack/react-router` 1.170.46, `@tanstack/react-query` 5.107.1,
`oidc-client-ts` 3.5, TypeScript 7.0.2 (native) and 6.0.3, Biome 2.6.8, `@hey-api/openapi-ts` 0.99.0.

## Decision table

| # | Question | Result | Evidence | Decision |
|---|---|---|---|---|
| 1 | Vite 8 + `@module-federation/vite` on a production build | **Pass** | `node check.mjs`: `PASS Q1 remote renders from runtime-registered manifest`, `PASS Q1 one React instance (shell === remote)`, `PASS Q1 router singleton nested provider renders page`; the shell registers the remote from a registry feed read at runtime (`registerRemotes` + `loadRemote`), sharing `react`, `react-dom`, `@tanstack/react-query` and `@tanstack/react-router` as singletons; both builds are `vite build` output, not dev servers | **Vite 8 + `@module-federation/vite` 1.24.1.** No Rsbuild fallback needed |
| 2 | Router composition | **Pass, shape A** | `node check.mjs`: deep link `/console/r/hello/items/42` survives a reload; back and forward cross the shell/remote boundary in both directions; `after.host === before.host && after.layout === before.layout` and `mounts` unchanged across remote navigations (the shell does not re-render); the shell's active menu follows the URL and clears on a shell page | **Shape A**: the shell owns the router with a splat route `/r/$remote/$`; the remote creates its own router with `basepath` on the shell's history, passed in the context |
| 3 | Integrity | **Pass, with a recorded limit** | `node check.mjs`: `PASS Q3 every manifest fetch carried the registry integrity` (`sha384-…` on the `mf-manifest.json` fetch); a server run with a deliberately wrong hash (`BAD_INTEGRITY=1`, `node check-integrity.mjs`): `PASS Q3 a manifest that differs from the registry hash is not mounted` — the shell shows `remote failed: [Federation Runtime]: Failed to get manifest` and mounts nothing | **`fetch` plugin**: the shell registers an MF runtime `fetch` plugin that attaches the registry's `integrity` to the manifest request, so the bytes that are verified are the bytes that are parsed. **Limit**: the manifest lists chunk paths but carries no per-chunk hash, so chunk bodies are not covered. The controls for those are the same-origin allow-listed entry path (`/console/remotes/<name>/<version>/`), immutable versioned directories and a CSP with `script-src 'self'` |
| 4 | Independent deploy | **Pass** | `node check-deploy.mjs`: building remote v2 leaves the shell build hash unchanged (`shell=f94cca192b812c1e` before and after); a tab on v1 keeps working after v2 is active; a new load gets v2; a reload moves the old tab to v2; flipping back to v1 is the same registry edit with no build | Versions are immutable directories; the registry flip is the only action; no shell rebuild or redeploy |
| 5 | Toolchain | **Pass on TypeScript 7.0.2** | `pnpm exec openapi-ts -f openapi-ts.platform.config.ts` and the billing config: both generate, and a second run is byte-identical (`GENERATION IDENTICAL`). The flat names are literal keys in the output: `'filter[created_at][eq]'?: string`, `'filter[created_at][in]'?: string`, … in both `types.gen.ts` files. `tsc` 7.0.2 typechecks the shell, the remote and the generated client (`exit=0`); deposits of `zod.gen.ts` included. `tsc` 6.0.3 also types it. Biome 2.6.8 runs; the generated client is excluded from lint because generated formatting is not ours to own | **TypeScript 7.0.2** for `tsc --noEmit` only; keep 6.0.3 as the documented fallback. `@hey-api/openapi-ts` 0.99 + Biome 2 confirmed |
| 6 | Auth hand-off | **Pass**, with a defect found in Platform's `/me` | `node check-login.mjs`: the shell signs in through Keycloak (`platform:admin@platform.test`), Keycloak redirects back with a code (PKCE `S256`, `client_id=suite-console`), `oidc-client-ts` exchanges it for a token, and the remote calls `GET /platform/api/v1/me` with the token it got from `ctx.getToken()` | The context object carries `getToken`; only the shell imports the OIDC library. The token lives in memory (`InMemoryWebStorage`). **Defect**: `/me` answers `404` for a real user because `MeController` reads `Actor.displayName()` and uses it as the Keycloak subject. Fixed in phase 3 (see below) |

## Defect found by the spike

`platform/contexts/access/.../auth/web/MeController.java` resolves the caller with
`queries.find(currentActor.get().displayName())`; `MeQueries.find` takes a Keycloak subject, and the actor's
`displayName` is the user's display name. The lookup therefore misses for any user whose display name differs from
their subject. Phase 3 changes the controller to look the caller up by `currentActor.get().id()` through
`UserReadRepository.findById`, which the adapter already exposes, and adds a test for it. Until that lands, `/me`
answers `401` without a token and `404` with one; the spike's Q6 evidence stops at "the shell obtained a token and the
remote called `/me` with it".

## Limits written down for later phases

- The registry's `integrity` covers `mf-manifest.json` only. Chunk bodies rely on the same-origin entry path, the
  immutable version directory and `script-src 'self'`. Phase 5 must ship exactly that CSP.
- The spike shell stores tokens in memory and has no callback route handling or refresh; that is phase 6 (`@suite/auth`)
  and phase 7 work, not a spike result.
- The spike's static server proxies `/platform/*` into Platform with the prefix stripped, matching the Caddy rule.
  Phase 5 owns the real `console-static` service and headers.

## Housekeeping

Both spike listeners (`:5173`, `:5174` and the throwaway `:5175`) were stopped; `lsof -i :5173 -i :5174 -i :5175`
returns nothing. No secret is written in this report: the spike logged in with the development bootstrap account from
`.env` and no credential is quoted here.
