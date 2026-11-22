---
title: Email-only console sign-in via a Keycloak tenant username form
date: 2026-11-22
summary: "The console names the tenant from its host and Keycloak's form adds the tenant prefix, so people type only an email; full-stack e2e was not run."
---

# Email-only console sign-in via a Keycloak tenant username form

## What happened

Console sign-in required typing `{tenant-code}:{email}` even though the host already names the tenant. The stored
username stays `{tenant-code}:{email}`; only the way it is reached changed.

- `ops/keycloak/provider/`: a Keycloak 26.8.2 authenticator (`TenantUsernamePasswordForm`) that reads the `tenant`
  authorization parameter (client note `client_request_param_tenant`), validates it against the TenantCode format and
  looks the person up as `<tenant>:<typed>`. Without a valid tenant it behaves as the stock form, so `suite-smoke`,
  direct Keycloak visits and a bare `localhost` still take the full username. A typed `other:email` becomes
  `tenant:other:email` and finds no one.
- `ops/keycloak/theme/suite/`: login theme (copy of keycloak.v2 `login.ftl`) with the title "Sign in to <host>" and an
  Email label, driven by the `suiteTenant` form attribute the authenticator sets.
- `realm-export.json`: `suite-browser` flow bound as the browser flow, `loginTheme: suite`.
- `ops/terraform/keycloak/Dockerfile` is now multi-stage (builds the jar, copies theme and realm); Compose builds the
  same image for Keycloak.
- Console: `frontend/packages/auth` sends `tenant=<first host label>` through `extraQueryParams` (none for bare hosts
  and IPs). e2e `signIn` takes an email; new `14-tenant-sign-in.spec.ts`.
- Docs: `docs/authentication-and-authorization.md`, README sign-in section, seed summary, Terraform README.

## Verification

- Provider unit tests and the jar build pass. A real Keycloak 26.8.2 container built from the new Dockerfile passed 10
  scripted browser-flow checks: tenant page and Email label, same email in two tenants reaching only the host's own
  identity, cross-tenant password refused, typed foreign username refused, platform host, fallback to the full
  username, malformed tenant ignored, and the tenant title kept after a wrong password.
- Against the built console, a headless browser at `platform.localhost:8090/console/` was sent to Keycloak with
  `tenant=platform`, and the page showed "Sign in to platform.localhost" and "Email".
- Frontend tests, typecheck and lint of the touched packages pass. Repo-wide lint has one existing format error in
  `packages/remote-kit/src/record/row-list.test.tsx`, a file this work did not touch.

## Not verified

The full-stack e2e (`14-tenant-sign-in` and the 14 updated sign-in call sites) did not run. Platform's bootstrap
administrator exists in the Platform database but not in the freshly created Keycloak, and restoring it needs the
password from `.env`, which was not read. The 13 existing specs were edited mechanically (prefix removed) and are only
typechecked and linted.

## Side effects

- Switching Compose's Keycloak from `image:` to `build:` recreated the container. Keycloak's `start-dev` data lives in
  the container, so its users were lost while the Platform database kept its users. `make up` and `make seed` then
  cannot sign in as the bootstrap administrator until the Keycloak side is recreated (for example `make clean`, then
  `make up` and `make seed`).
- The local `.env` appears to hold `KEYCLOAK_REALM=tenancykit` while the realm is `billing`, which broke Platform's
  Keycloak admin calls and the e2e login; it was overridden on the command line, not edited.
- The stack started for verification was stopped with `make down`; data volumes were kept.

## Next steps

- Run the e2e suite after a clean stack start and fix any spec that still assumed the prefix.
- Fix `KEYCLOAK_REALM` in the local `.env`.
- An existing realm keeps the old form until the flow is rebound (documented).
- Keycloak logs that the authenticator SPI is internal and may change on upgrade; re-check the provider on each bump.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
