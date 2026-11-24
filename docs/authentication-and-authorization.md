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

Keycloak is configured to allow duplicate emails. Usernames are unique within a realm, so the stored identifier is the
Tenant-scoped username `{tenant-code}:{email}`; Platform users use the reserved code `platform`. The realm is defined
by `ops/keycloak/realm-export.json`.

A person never types that prefix. The console takes the tenant from its host (the first label: `acme.example.com` is
`acme`, `platform.example.com` is `platform`) and adds `tenant=<code>` to the authorization request. The realm's
browser flow, `suite-browser`, uses the Tenant Username Password Form, a Keycloak provider built from
`ops/keycloak/provider/`, which looks the person up as `<tenant>:<typed email>`. The sign-in page, from the `suite`
login theme in `ops/keycloak/theme/`, says which host it is signing in to and asks for an email. Whatever the person
types is always prefixed, so `other:email` typed at one tenant's host finds no one, and an email shared by two tenants
reaches only the host's own identity. The tenant is a request parameter a visitor could change, which is harmless: it
selects which identity to check a password for, and the apps still require the token's `tenant_code` to match the host.

The sign-in theme follows the design system: the image build copies `frontend/packages/ui/src/styles/tokens.css` into
it, so colours, radii, control sizes and dark mode come from `design/tokens.json`, and the page uses the console's
Inter font. From 960px up the page has an aside that introduces the suite and its nine apps, each in its design-system
accent; below that it is the card alone. The aside makes the theme carry its own copy of `template.ftl` (the keycloak.v2
frame plus the aside), so check it against the stock template on every Keycloak upgrade, as with `login.ftl`.

Without a tenant parameter (a visit straight to Keycloak, the `suite-smoke` client, a bare host such as `localhost`) the
form is the stock one and takes the full `tenant:email` username. A `tenant` that is not a well-formed tenant code is
treated as absent. Both are built into the Keycloak image by `ops/terraform/keycloak/Dockerfile`, which Compose builds
too. The realm import runs only when the realm does not exist, so an existing realm needs the same change by hand: in the
admin console, duplicate the browser flow under Authentication, replace its Username Password Form with the Tenant
Username Password Form, bind the copy as the browser flow, and set the realm's login theme to `suite`. Until then the old form keeps working and still
takes the full username.

Platform creates the Keycloak identity when it creates a user, and removes it again if storing the user fails. A new
identity has a temporary password that Keycloak refuses to hand out from a direct grant until it is changed; a user
signs in through the normal flow and is then active.

## Forgetting a password

The sign-in page links to the realm's reset-credentials flow, `suite-reset-credentials` (`resetPasswordAllowed` is on).
Its first step is the Tenant Choose User step from the same provider: the person types only the email, and the user
looked up is `<tenant>:<typed email>`, from the same `tenant` parameter the sign-in used. An email with no account, or a
tenant that does not exist, gets the same "you should receive an email" answer as a known one and sends nothing, so the
page does not say who has an account. The link in the mail is valid for 15 minutes
(`actionTokenGeneratedByUserLifespan.reset-credentials` in the realm), and the flow also resets a one-time code when the
person has one.

The realm holds no SMTP settings. The mail Keycloak renders leaves through the provider's email sender, which publishes
an `email.send` command to `mailer.commands` as the broker user `keycloak`, so [mailer](mailer.md) stays the only
service that calls AWS and every reset appears in its per-tenant delivery ledger. The sender needs the tenant's id, which
Keycloak does not otherwise know, so Platform writes it on every identity as the admin-only attribute `tenant_id` when it
creates the identity (a new Tenant's id is chosen before its administrator's identity is created). A user without one
cannot be mailed: Keycloak logs `has no tenant_id attribute` and still shows the neutral answer. Identities created
before the attribute existed are filled in by `ops/keycloak/backfill-tenant-id.sh`, which reads `code,id` pairs from
standard input, changes nothing unless `APPLY=1`, and writes each user's previous representation to a backup directory
first.

The sender is configured on the server, not the realm, with `KC_SPI_EMAIL_SENDER__SUITE_MAILER__<NAME>` variables
(`HOST`, `PORT`, `USERNAME`, `PASSWORD`, `VHOST`, `TLS`, `EXCHANGE`, `ROUTING_KEY`, `FROM_KEY`); Compose and the
Terraform task set the first four, and Terraform turns `TLS` on for Amazon MQ. A failed publish is an error Keycloak
logs (`failedToSendPwdResetEmail`) while the person still sees the neutral answer. Mailer has to be running for a mail
to be delivered. As with the browser flow, the realm import runs only when the realm does not exist, so an existing
realm needs the reset flow added and bound by hand, the `tenant_id` attribute declared in its user profile, and the
sender's variables set.

## The console's sign-in

The Suite Console ([console.md](console.md)) signs a person in with the public Keycloak client `suite-console`
(authorization code with PKCE S256, no secret) and holds the tokens in the shell's memory: a remote pulls in script
from the registry, and a token in storage would be readable by it. A reload signs in again through Keycloak's own
session, which costs a redirect, not a password.

Keycloak accepts a wildcard only at the end of a redirect URI, so the client's redirect targets are the hosts that
exist: the platform host and every active Tenant's host, each as `http://<host>/console/*` (plus the console port when
it is not the scheme's default). Adding a Tenant or a domain registers its host; the reconciler in Platform makes the
whole set equal the hosts that exist, and the domain add is what keeps a new Tenant usable immediately. The web
origins are the single entry `*`: a public PKCE client gains nothing from a per-host list, and Keycloak writes the
origins into every access token (`allowed-origins`), so one per Tenant would grow each person's token with every
Tenant the suite ever creates.

Where a person may go is still the backend's decision: the shell reads `/api/v1/me` and the console feed, and every
call carries the user's own token.

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
  [contracts/openapi/tenant-system/billing.yaml](../contracts/openapi/tenant-system/billing.yaml), generated from the
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
`POST /system/v1/billing-sessions` (`billing:billing-session:create`). The single-use token in the portal URL is
exchanged for an HttpOnly, Secure, SameSite cookie; the session principal carries the Tenant, that Customer and the
session permissions only. Every portal query takes the Customer from the principal, and a session issued in one Tenant
is not usable in another. State-changing calls use a double-submit CSRF token, and CORS is limited to the tenant
hostname. See [billing-session-and-portal.md](billing-session-and-portal.md).

## Token and session checks

The token checks (issuer, audience, expiry, subject), the two issuers an app accepts, and the rule that a session from
one Tenant cannot be used in another are listed in [security-and-reliability.md](security-and-reliability.md).
