---
title: "Phase 4: Keycloak redirect sync"
status: completed
priority: P1
effort: "2d"
dependencies: [1]
---

# Phase 4: Keycloak redirect sync

## Goal

A tenant user can log in to the Tenant Shell on any of the tenant's hostnames. Keycloak accepts a wildcard only at the
end of a redirect URI, so Platform keeps the redirect URIs and web origins of the `suite-console` client in step with
the tenant domains it already manages.

## Context

- `suite-console` has `http://localhost:5173/*` and `http://platform.localhost:5173/*` only
  (`ops/keycloak/realm-export.json`).
- `KeycloakIdentityProvider` (`platform/contexts/access/src/adapter/java/dev/ngb/platform/access/adapter/auth/keycloak/`)
  already uses the `platform-admin` client for the Keycloak Admin API (create and delete user).
- Tenant domains are added by `POST /api/v1/tenants/{id}/domains` (`tenancy` context).
- Alternative considered and not taken: a single fixed auth origin that redirects back to the tenant host. It avoids
  the sync but adds a hop and a cross-origin hand-off for every login; revisit only if the sync proves fragile.

## Files

- Modify: `IdentityProviderPort` and `KeycloakIdentityProvider` (new `registerConsoleHost(host)` and
  `removeConsoleHost(host)`), the use cases that add and remove a tenant domain in `platform/contexts/tenancy`, the
  startup wiring in `platform/apps/app-boot/.../boot/`, `platform/apps/app-boot/src/main/resources/application.yml`
  (console scheme and client id), `ops/keycloak/realm-export.json`.
- Create: a reconciler (a scheduled job using the existing `job_leases` lease) that makes the client's URIs equal the
  set derived from all active tenant domains, plus tests mirroring the production packages.

## Steps

1. Read the domain add and remove use cases and the existing scheduled jobs to reuse the lease pattern.
2. Port: add the two methods with an in-memory fake for application tests; the Keycloak adapter does `GET` the client by
   `clientId`, adds or removes `http(s)://<host>/console/*` in `redirectUris` and `<scheme>://<host>` in `webOrigins`,
   and `PUT`s the whole representation back (the Admin API replaces, it does not merge); retry on a conflicting update.
3. Call it after the domain change commits, never inside the transaction; a failure is logged and left to the reconciler,
   it must not fail the domain request.
4. Reconciler: compute the desired URI set from active domains plus the platform host and the dev entries, diff, apply
   once; idempotent, safe on every instance because of the lease.
5. Realm export: add `http://localhost/console/*` and `http://platform.localhost/console/*`, keep the 5173 entries for
   local dev, set post-logout redirect URIs, and give the `platform-admin` service account the `manage-clients` role
   if it lacks it.
6. Tests: adapter test with a stubbed Keycloak (follow `KeycloakIdentityProviderTest`), add and remove idempotency,
   concurrent update retry, reconciler diff.

## Todo

- [x] Port and Keycloak adapter methods
- [x] Hook on domain add and remove after commit
- [x] Reconciler with lease
- [x] Realm export updated
- [x] Tests green

## Verification

```bash
cd platform && ./gradlew :contexts:access:test :contexts:tenancy:test :apps:app-boot:test
```

Then, on the compose stack: add a domain to a tenant and read the client back from the Keycloak Admin API; the new
`redirectUris` entry is present, and removing the domain removes it.

## Risks

The Admin API replaces the client document, so two concurrent changes can lose one; the retry and the reconciler are
the guard, and the reconciler is the source of correctness. The redirect URI list grows by one entry per tenant domain,
which is acceptable for this suite and recorded as a scale limit in the docs.

## Security

Keycloak is told to trust a redirect target, so only hosts that exist as active tenant domains are ever added, and a
removed or deactivated tenant's host is removed. The `manage-clients` role goes to the `platform-admin` service account
only, never to a user.
