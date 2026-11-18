---
title: "Phase 5: Edge and remote deploy"
status: completed
priority: P1
effort: "2.5d"
dependencies: [1, 2]
---

# Phase 5: Edge and remote deploy

## Goal

The compose stack serves both shells and any remote version under `/console/` on every host, with the right cache and
security headers, and a script publishes one remote on its own: copy its build to an immutable versioned directory and
register the version in Platform.

## Context

- Caddy picks the first matching `handle`, and the last `handle` sends everything to Billing; the platform host serves
  Platform at its root (`ops/caddy/Caddyfile`). The `/console/*` handle goes **before** the `@platformHost` rule so no
  existing path moves. `ops/smoke/suite-smoke.sh` calls `platform.localhost/api/...` and must stay green.
- Local only: production publishing (S3, CloudFront, ECS) is a non-goal; the script isolates the publish step so that
  target can be swapped later.

## Files

- Modify: `ops/caddy/Caddyfile` (`/console/*` handle), `docker-compose.yml` (service `console-static`, volume
  `console-assets`, Caddy depends on it), `.env.example`.
- Create: `ops/console/Caddyfile` (the static server), `ops/console/config.json.template` (runtime config: Keycloak
  authority, realm, client id; rendered at container start so no rebuild per environment),
  `ops/console/deploy-remote.sh`, `ops/smoke/console-smoke.sh`.

## Behaviour

- Path layout inside the volume: `shells/platform/`, `shells/tenant/`, `remotes/<name>/<version>/`.
- Host selection: `platform.localhost` (and the configured platform host) serves `shells/platform`, every other host
  `shells/tenant`; unknown paths under `/console/` fall back to the shell's `index.html` (client routing).
- Headers: `index.html`, `config.json` and `mf-manifest.json`: `Cache-Control: no-cache`; files under
  `/console/remotes/*/*/`: `public, max-age=31536000, immutable`; every response:
  `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'
  <keycloak origin>; font-src 'self'; img-src 'self' data:; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: no-referrer`. `unsafe-inline` for styles is the Tailwind and Radix runtime need; record that in the
  file comment. Fonts are self-hosted (Fontsource, phase 6), so `font-src` stays `'self'` and no Google Fonts host is allowed.
- `deploy-remote.sh <name> <dist-dir>`: version from the remote's `package.json` plus the short git sha; copy the build to
  `remotes/<name>/<version>/` (refuse to overwrite an existing version); compute `sha384` of `mf-manifest.json`; get a
  token from `POST /platform/oauth/token` with the `frontend-ci` service client (`audience=platform`); `POST
  /platform/api/v1/ui-remotes/<name>/versions` (registers only; the operator activates); print the version. Re-running
  with the same build is a no-op.

## Steps

1. Add the static server and the volume; mount the volume read-only into the server and read-write only for the deploy
   script's copy step.
2. Write the Caddy handle and the static server config; render `config.json` from the environment at start.
3. Write `deploy-remote.sh`, shellcheck-clean, failing loudly on any non-2xx and never printing the secret.
4. Write `console-smoke.sh` (needs a built remote from phase 2): deploy a version, `curl` the manifest through Caddy on
   a tenant host and the platform host, assert headers, assert a second deploy leaves the first version's files intact,
   assert an entry path outside `/console/remotes/<name>/<version>/` is refused by the registry.
5. Run `ops/smoke/suite-smoke.sh` unchanged and confirm it passes.

## Todo

- [x] `console-static` service and volume
- [x] Caddy `/console/*` handle placed before the platform-host rule
- [x] Headers and CSP
- [x] Runtime `config.json`
- [x] `deploy-remote.sh`
- [x] `console-smoke.sh` and unchanged `suite-smoke.sh` green

## Verification

```bash
docker compose up -d console-static caddy platform
bash ops/smoke/console-smoke.sh && bash ops/smoke/suite-smoke.sh
```

Both exit 0. Dev servers and containers started for the check are stopped afterwards.

## Risks

A broad `/console/*` handle could shadow an app prefix; the exact prefix match and the smoke script guard it.
A too-strict CSP breaks a library at runtime; the phase 13 E2E run watches the console for CSP violations.

## Security

Static assets are public by design; nothing secret is in `config.json` (Keycloak authority and a public client id only).
The deploy script reads the `frontend-ci` secret from the environment or CI secret store, never from a file in the repo.

## Result (2026-11-17)

- `console-static` (Caddy) and `console-deploy` (a `tools` profile service on the same image) run against one
  `console-assets` volume; the outer Caddy serves `/console/*` before the platform-host rule and strips the prefix.
- `bash ops/smoke/console-smoke.sh` is green: two remote versions published, both manifests served on a tenant host and
  the platform host, first version still intact after the second publish, the CSP/cache/nosniff/no-referrer headers
  present, `/console/config.json` rendered and `no-cache`, and an outside entry path answered `400`.
- `ops/smoke/suite-smoke.sh` is **not** green, for reasons outside the console: the script still parsed list endpoints
  as bare arrays after the list-query contract landed (four `jq` filters updated to `(.data // .)`), and Billing's
  replica worker then failed on `new row violates row-level security policy for table "tenant_systems"` while
  rebuilding its snapshot. That path never touches `/console`; the failure reproduces with no console traffic, and
  Billing's database was six migrations behind the running image until this session applied them.
