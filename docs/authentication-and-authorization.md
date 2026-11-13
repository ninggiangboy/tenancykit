# Authentication and Authorization

Keycloak authenticates. Platform authorizes. Apps enforce.

## Authentication

Keycloak is the shared Identity Provider for the whole suite.

- A single shared Keycloak Realm.
- Integration through OAuth 2.0 and OpenID Connect.
- Keycloak owns authentication, credentials, temporary passwords and login sessions.
- Platform links a Keycloak identity to its own user through the OIDC subject; an app never does.
- A user's identity is distinguished per Tenant. Email is never the sole identifier across the suite, and the same
  email may exist in several Tenants under independent identities.

Keycloak is configured to allow duplicate emails. Usernames are unique within a realm, so the login identifier is the
Tenant-scoped username `{tenant-code}:{email}`, built from the hostname; Platform users use the reserved code
`platform`. The realm is defined by `ops/keycloak/realm-export.json`.

Platform creates the Keycloak identity when it creates a user, and removes it again if storing the user fails. A new
identity has a temporary password that Keycloak refuses to hand out from a direct grant until it is changed; a user
signs in through the normal flow and is then active.

## Platform Users and RBAC

A Platform User is an account used to administer the suite. A Tenant User administers and operates the apps of one
Tenant. They are not the same as a Tenant System's business user, which an app sees only as a Customer mapping (see
[customers-and-tenant-mapping.md](customers-and-tenant-mapping.md)).

Authorization is centralized RBAC in Platform:

| Part | Meaning |
|---|---|
| User | An account of a person, at `platform` or `tenant` scope |
| Role | A customizable group of permissions |
| Permission | The right to perform one action, named `<app>:<resource>:<action>` |
| Role Assignment | Assigns a role to a user within a scope |
| Role Permission | Which permissions belong to a role |
| User Permission | A permission granted directly to a user |
| Permission catalog | `contracts/permissions/<app>.yaml`: the codes an app declares, with the scopes that may hold them and whether a Tenant System credential may carry them |

Two scopes exist: `platform` administers the whole suite and its Tenants; `tenant` administers one Tenant. A user can
hold several roles and permissions in both scopes. Roles are managed dynamically through the database, never
hardcoded; Platform seeds a Platform Administrator role and, per Tenant, a Tenant Administrator role holding every
tenant-scope code.

A role may hold only a code whose catalogued `scopes` include the role's own scope, and a caller may create, rename,
assign or grant only a role whose scope is no wider than their own — a Tenant Administrator holding every tenant-scope
code still cannot mint a `platform` role or hand out a right they do not hold. The acting tenant is derived from the
authenticated actor, never from a request body. `contracts/permissions/` is the only list of codes Platform accepts,
and a test compares the codes the controllers declare with it.

Platform resolves a user's effective permission set from their roles and direct grants and puts it in the suite token
or the `/api/v1/me` answer. An app receives the codes for its own app only: a `billing`-audience token carries only
`billing:*` codes, filtered at issuance, so a code for another app can never appear.

## Suite tokens (machine to machine)

A Tenant System is a third party that does not share the Platform's identity provider, so it does not authenticate
through Keycloak. It exchanges a Platform-issued client credential for a short-lived suite token.

- Platform issues the credential: a key id and a secret, one per Tenant System, stored as a hash and shown once. A
  Tenant User creates, rotates (with an overlap window) and revokes it; a Tenant System never creates its own. A
  rotate and a revoke run under a lock on the Tenant System and a version guard on the credential, so concurrent
  rotations cannot leave more than two live credentials and a rotation can never resurrect a revoked one. Every
  access command records exactly one audit entry.
- A service client (`PLATFORM_SERVICE_CLIENT_ID`) works the same way for a trusted service, such as Billing's replica
  rebuild; its secret's SHA-256 is configuration.
- The grant is `POST /oauth/token` with HTTP Basic client authentication, `grant_type=client_credentials` and
  `audience=<app>`. A tenant-system token carries `tenant_id`, `tenant_code` and `tenant_system_id`; its `scope` holds
  only the granted codes for that app. The token lives 10 minutes.
- An app derives the Tenant and the Tenant System from the token, never from a client-supplied `tenant_id`, and
  checks the Tenant System is active in its replica. Ownership bounds what a Tenant System may see: it reaches only
  the Customers it mapped and their wallets, reservations, charges, payments, usage and ratings; a resource owned by
  another Tenant System in the same Tenant answers 404. A Tenant User keeps the tenant-wide view.
- The operations a Tenant System may call are published in
  [contracts/openapi/billing-tenant-system.yaml](../contracts/openapi/billing-tenant-system.yaml), generated from the
  same code as the full contract. Each operation carries its required permission and its allowed callers.
- Revocation latency: a revoked credential's existing tokens stay valid until they expire (at most 10 minutes);
  disabling the Tenant System is immediate, because every app re-checks `tenant_systems.status` on each request.
- Rejected: a Keycloak client per Tenant System, permissions inside Keycloak tokens, and per-request introspection of
  every call. The suite token carries its own codes and the app enforces them from its replica, so a request costs no
  call to Platform.

## Enforcement from the replica

An app does not call Platform per request. It keeps a replica of the few Platform facts it needs — `tenants`,
`tenant_domains`, `tenant_systems`, `user_grants`, `app_entitlements` — rebuilt from the snapshot API and kept current
by `platform.events`. On each request the app resolves the host to a Tenant, verifies the token, reads the actor's
grants and entitlement from its replica, and enforces the `@RequiresPermission` code against them.

The trade is revocation latency for a cheap request path. Revoking a role at Platform reaches an app when its
`user-access.changed` event arrives, typically well under a second; the suite smoke test asserts it inside 5 seconds.
The alternative, asking Platform on every request, would add a network hop and a failure mode to every call, and was
rejected.

## Billing Session (Customer Portal)

The Customer Portal has no login. A Tenant System issues a Billing Session for one mapped Customer through
`POST /api/v1/billing-sessions` (`billing:billing-session:create`). The single-use token in the portal URL is
exchanged for an HttpOnly, Secure, SameSite cookie; the session principal carries the Tenant, that Customer and the
session permissions only. Every portal query takes the Customer from the principal, and a session issued in one Tenant
is not usable in another. State-changing calls use a double-submit CSRF token, and CORS is limited to the tenant
hostname. See [billing-session-and-portal.md](billing-session-and-portal.md).

## Token and session checks

The token checks (issuer, audience, expiry, subject), the two issuers an app accepts, and the rule that a session from
one Tenant cannot be used in another are listed in [security-and-reliability.md](security-and-reliability.md).
