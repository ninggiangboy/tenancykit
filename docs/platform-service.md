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
| `GET /api/v1/me` | The caller's enabled apps and effective permission codes |

`/api/v1/me` answers the app switcher and the app's own checks; it is the one place a user's whole access is visible.

## Token issuance

`IssueTokenUseCase` authenticates the caller, checks a disabled Tenant System, filters the credential's scopes to the
audience, and asks `SuiteTokenIssuer` to sign a 10-minute JWT. The claims are documented in
[tokens/README.md](../contracts/tokens/README.md). The token endpoint is rate-limited per client id, with a fixed set
of shared buckets for unauthenticated attempts, so an invented client id can neither exhaust a real one's budget nor
create an unbounded number of rows. Every failed attempt is audited.

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

## Security

- Keycloak authenticates; Platform resolves a user to their grants and never trusts a token's own `scope` claim for an
  app-level permission.
- A request acts in exactly one scope, the Platform's or one Tenant's, fixed by the hostname before the security chain
  runs. A client-supplied tenant id is never consulted.
- The application connects as the unprivileged `platform_app` role; Flyway migrates as `platform_owner`. Row-level
  security is forced, so a code path that forgets the tenant scope fails closed.
- No secret in a committed file: the service client's secret is stored as its SHA-256, and the signing key comes from
  the environment.
