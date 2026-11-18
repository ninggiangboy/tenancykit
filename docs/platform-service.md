# Platform service

Platform is the suite's authority on identity, tenancy, access and integration. It is the only service that mints a
suite token, and the only one that decides who may do what. It is its own Gradle build, database and process; every
app enforces what Platform grants.

## What it owns

| Context | Owns | Publishes |
|---|---|---|
| `tenancy` | Tenants, tenant domains, app entitlements | `tenant.changed`, `tenant-domain.changed`, `entitlement.changed` |
| `access` | Users, roles, role permissions, role assignments, direct grants; `/api/v1/me` | `user-access.changed` |
| `integration` | Tenant Systems, their API credentials, the token endpoint | `tenant-system.changed` |

## Surface

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/tenants` | Provision a Tenant with its first administrator and seed the Tenant Administrator role with every tenant-scope code |
| `GET/PUT/POST /api/v1/tenants/**` | List, rename, deactivate a Tenant; add a domain; enable or disable an app for it |
| `/api/v1/users`, `/api/v1/roles` | Users and RBAC: create, list, assign roles, edit permissions, grant or revoke a code directly |
| `/api/v1/tenant-systems`, `…/{id}/credentials` | Tenant Systems and their credentials: create, rotate (with an overlap window), revoke |
| `POST /oauth/token` | The client-credentials grant for a service client or a Tenant System |
| `GET /.well-known/jwks.json` | The public halves of the signing keys, so any app verifies a suite token |
| `GET /internal/replica/{stream}` | The snapshot API: one page of a replica stream |
| `GET /api/v1/me` | The caller's scope, tenant and effective permission codes |
| `/api/v1/ui-remotes`, `…/{name}/versions` | The UI remote registry: list, enable or disable, activate a version, register a version (CI) |
| `GET /api/v1/console/remotes` | The shells' feed: the remotes this caller may use, filtered on the server |
| `GET /api/v1/tenants/{tenantId}/apps` | A Tenant's app entitlements, for the tenants screen and its switches |

`/api/v1/me` answers the app switcher and the app's own checks; it is the one place a user's whole access is visible.

## Token issuance

`IssueTokenUseCase` charges the rate-limit bucket of the claimed client id before it compares the secret, so a guess
costs budget whether or not it is accepted. A request that did not authenticate then pays the caller's address and
one shared anonymous bucket, which has its own, wider allowance; an invented client id therefore buys no budget of
its own and an unbounded number of buckets cannot be created. Then the caller is authenticated: a disabled Tenant
System is refused, the credential's scopes are filtered to the audience, and `SuiteTokenIssuer` signs a 10-minute
JWT. The claims are documented in [tokens/README.md](../contracts/tokens/README.md). Every failed attempt is audited.

The signing key is an ES256 private key in PEM form (`PLATFORM_TOKEN_SIGNING_KEY`). During rotation the previous key
stays in the JWKS for one token lifetime. Development may use an ephemeral per-process key; that option is refused
unless `PLATFORM_TOKEN_ALLOW_EPHEMERAL_KEY=true`.

## The snapshot and its streams

Every app keeps a replica of the Platform facts it needs. Two sources fill it: the snapshot API for a rebuild from
zero, and `platform.events` for live changes. Both deliver the same JSON, so a rebuild and a live event apply through
one writer.

| Stream | Table in the consumer | Conflict key |
|---|---|---|
| `tenants` | `tenants` | `id` |
| `tenant-domains` | `tenant_domains` | `id` |
| `tenant-systems` | `tenant_systems` | `id` |
| `user-access` | `user_grants` | `user_id` |
| `entitlements` | `app_entitlements` | `(tenant_id, app_code)` |

The rebuild order is `tenants`, `tenant-domains`, `tenant-systems`, `user-access`, `entitlements`, because a child row
references its parent. A page is bounded so one tenant or one aggregate's children never split across a boundary in a
way the writer cannot apply. The details of the protocol are in [suite-messaging.md](suite-messaging.md).

## The UI remote registry

The console is a set of independently deployed UIs, and Platform is what decides which one a browser runs. Two tables
hold it: `ui_remotes` (name, app code, title, navigation order, audiences, `enabled`, `active_version`) and
`ui_remote_versions` (an immutable version, its manifest path, its `sha384` hash and the shell-sdk major it was built
against). The versions of a remote are its history; the active version is the one the shells load.

- A remote's CI registers a version with the `frontend-ci` service client, which holds exactly
  `platform:ui-remote:register`. Registering never activates: an operator enables the remote and activates a version
  with `platform:ui-remote:manage`, and pointing it back at an older version is the rollback.
- The entry path is allow-listed to `/console/remotes/<name>/<version>/`; anything else is `400` and nothing is
  stored. The shell verifies the stored hash on the manifest it loads.
- The feed answers only what the caller may use: enabled, for the caller's audience, and — for a Tenant — with the
  app's entitlement on. A Tenant's app entitlement is the same fact that makes its APIs answer.
- The `frontend-ci` secret is `FRONTEND_CI_SERVICE_CLIENT_SECRET`; Platform stores its SHA-256 in
  `PLATFORM_FRONTEND_CI_SERVICE_CLIENT_SECRET_HASH`. Rotate both together — the client is a configuration entry, so no
  code changes.

## The console client's redirect targets

Keycloak accepts a wildcard only at the end of a redirect URI, so a Tenant host cannot be wildcarded: the
`suite-console` client's redirect URIs are the hosts that exist. Adding a Tenant (its default subdomain) or a domain
registers the host with Keycloak right after the transaction commits, and a startup-and-every-five-minutes reconciler
makes the whole set equal the hosts that exist, so a Keycloak that was down repairs itself. The port the console is
published on is part of every entry (`platform.keycloak.console-port`). The client's web origins are the single `*`
entry: a public PKCE client gains nothing from a per-host list, and Keycloak copies the origins into every access
token, so one per Tenant would grow each person's token without bound.

## Security

- Keycloak authenticates; Platform resolves a user to their grants and never trusts a token's own `scope` claim for an
  app-level permission.
- A request acts in exactly one scope, the Platform's or one Tenant's, fixed by the hostname before the security chain
  runs. A client-supplied tenant id is never consulted.
- The application connects as the unprivileged `platform_app` role; Flyway migrates as `platform_owner`. Row-level
  security is forced, so a code path that forgets the tenant scope fails closed.
- No secret in a committed file: the service client's secret is stored as its SHA-256, and the signing key comes from
  the environment.
