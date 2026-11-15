# Notification

The suite's app for **notifying Platform users** about platform-side state changes. It is a separate deployable
(`notification/`, root package `dev.ngb.notification`) with three runtimes: `migrate`, `api` and `worker`.

It turns a `platform.events` state transfer into typed notifications, renders suite-wide templates, stores
in-app notifications and publishes **already-rendered** `email.send` commands to `mailer.commands`. Delivery stays with
[mailer](mailer.md); this service never speaks SMTP or SES.

"Platform user" means a user of this suite, at either scope: a Tenant User, and a Platform Administrator whose rows live
under the reserved Platform tenant. It never means a Tenant's own customer.

## What it is, and what it is not

| It is | It is not |
|---|---|
| The owner of the notification catalog, templates, locale, preferences, the in-app store and the dispatch ledger | A delivery service: mailer owns the transport, the retry and the delivery truth |
| A consumer of `platform.events` with its own replica of Platform data | A consumer of Billing data: it reads no business data from another app |
| A producer of exactly one command, `email.send` | A producer of events: it declares no exchange of its own and has no event anyone subscribes to |
| Suite content edited by a Platform user without a redeploy | A template editor UI, a rules engine, or a bulk/marketing tool |
| Email for a tenant user and a Platform administrator, plus a stored in-app row | SMS, push, Slack, Telegram or a real-time stream; customers are never notified |

The dated reversal is in [overview-and-scope.md](overview-and-scope.md): notifying the suite's own users crosses no
business-data boundary, so the service exists; notifying a Tenant's own customers does not, so that stays with the
Tenant System.

## Runtimes

| Profile | Runs | Migrates |
|---|---|---|
| `api` | HTTP: the user's inbox and preferences, the suite's templates, the tenant's settings and dispatch ledger | no |
| `worker` | the `platform.events` listener, the outbox relay and housekeeping; actuator probes only | no |
| `migrate` | nothing | Flyway as the table owner, then exits |
| default (no profile) | `api` and `worker` together, for dev and tests | yes |

Both long-running runtimes include `replicaState` in the readiness group. The worker is the only runtime that rebuilds
the replica, so on a fresh database the api stays `DOWN` until the worker has finished one full rebuild.

## Broker items

| Item | Value |
|---|---|
| Consumes | `platform.events` on the durable queue `notification.platform-events`, bound to the five state-transfer keys (`tenant.changed`, `tenant-domain.changed`, `tenant-system.changed`, `user-access.changed`, `entitlement.changed`) |
| Dead-letters | to `suite.dlx`, drained by `notification.platform-events.dlq`; the queue retries with exponential backoff and then rejects without requeue |
| Publishes | `email.send` to `mailer.commands` through the transactional outbox |
| Declares | no exchange of its own |

The broker user `notification` is the only service user with a cross-service publish grant (`mailer.commands`).
`ops/smoke/isolation.sh` asserts it as a refusal for every exchange it must not write and as a positive control for the
one it must.

## The five types and their audiences

| Type | Fires when | Audience |
|---|---|---|
| `ACCESS_GRANTED` | no previous grant row, current row `ACTIVE` | the subject user, at either scope |
| `ACCESS_CHANGED` | the permission set actually differs, current row `ACTIVE` | the subject user, at either scope |
| `ACCESS_DISABLED` | previous `ACTIVE`, current `DISABLED` | the subject user, at either scope (the in-app row is kept so they can read it if re-enabled) |
| `ENTITLEMENT_CHANGED` | an app entitlement row is new or its `enabled` value changed | tenant users holding `platform:user:manage` |
| `TENANT_DEACTIVATED` | previous tenant `ACTIVE`, current `DEACTIVATED` | every active tenant-scope user of that tenant |

A state-transfer event carries the full current row, never "this changed", so every rule compares the row before the
upsert with the row after it. The previous read happens **before** the upsert, inside the same transaction; reading it
afterwards yields the new state twice and no rule ever fires. Permission arrays are compared as sets, so a recompute
with identical permissions does not notify.

The three access types fire at both scopes, so a Platform Administrator is told when their own access is granted,
changed or disabled, exactly like a tenant user. Their rows live under the reserved Platform tenant
(`RequestScope.PLATFORM_TENANT_ID`), so the same tables, the same row-level security and the same dispatch path serve
them; there is no second mechanism. The two tenant-wide types resolve their audience from tenant scope only, because a
Platform administrator does not administer a tenant's entitlement or its deactivation.

## Templates, parts and locale

A type has five parts, all rendered together:

| Part | Contract |
|---|---|
| `email.subject` | at most 998 characters, no line break |
| `email.html` | escaped variables, authored markup |
| `email.text` | variables unescaped (`&amp;` in a plain-text body is a bug) |
| `inapp.title` | HTML-escaped |
| `inapp.body` | HTML-escaped |

The classpath ships a default for every type × `en`/`vi` × part. A stored override row for `(template_key, locale)`
replaces only the parts it defines; the rest come from the default. Templates are **logic-less by enforcement**: a
section, an inverted section, a partial, a closing tag or an unescaped interpolation is refused at write time and again
at load time, so a row inserted directly with SQL cannot smuggle logic in. An unknown variable is a hard failure, never
an empty string.

A template is **the suite's content, not a tenant's**. `notification_templates` has no `tenant_id` and no row-level
security, and one row per `(template_key, locale)` serves every tenant: all tenants receive the same email, and only a
Platform user at platform scope may edit it. That removes a tenant administrator's entire surface for rewriting the
wording of an email or injecting content into one. The cost is deliberate: a tenant cannot white-label its own email
copy. Restoring that would mean adding the column and the policy back and redoing the API, the permissions and the
tests.

The `version` in the dispatch ledger is the shared override's own revision, or `0` when the classpath default rendered
the message, so "which content produced this email" is answerable without a per-tenant version.

Because one row now affects every tenant at once, a typo by a Platform user reaches every tenant's inbox simultaneously.
The write-time and load-time policy above is what bounds that risk: the content is logic-less and strict, so a bad edit
fails loudly rather than rendering something unexpected.

Locale order is **user preference → tenant default → `en`**. An unsupported tag falls through; a bad preference never
blocks a notification. The override serving every tenant does not change this: the *content* is shared, the *locale*
each user receives is still chosen per user.

## Why a user did or did not get an email

One dispatch row per `(tenant, source event, type, user)` answers it:

| Dispatch status | Meaning |
|---|---|
| `PUBLISHED` | the `email.send` command was written to the outbox and is queued for mailer |
| `SUPPRESSED_MUTED` | the user muted this type; the in-app row is still stored |
| `SKIPPED_INACTIVE` | the user's account is disabled; the in-app row is still stored |
| `SKIPPED_NO_ADDRESS` | the replica has no usable address for the user |
| `PENDING` | the row exists but no command was queued |
| `DEAD` | the outbox gave up publishing the command |

Two dedupe layers are deliberate and neither is redundant: the inbox stops a redelivered envelope, and the unique
`(tenant, source event, type, user)` key stops a *different* envelope describing the same transition. There is no
coalescing window: a role edit emits one event per affected user, so a burst is one email per different user, and a
racy window that silently drops mail would be worse than the rare duplicate.

`PUBLISHED` is this service's statement. **Mailer's ledger is the only delivery truth**; `SUCCEEDED` there means
accepted by the transport, not delivered to an inbox.

## Replica rebuild

The service keeps the five Platform replica tables. On an empty database the worker pages Platform's snapshot, upserts
every row, writes `replica_state`, and only then starts the listener; messages that arrive meanwhile wait in the
durable queue and the version guard drops whatever the snapshot already covered.

To force a rebuild: delete the `replica_state` row and restart the worker. A failed page leaves no marker, so the next
boot retries the whole rebuild; the upserts are idempotent. Deleting only a child table's rows is the documented blind
spot and is treated as an incident to force a rebuild on.

The replica client is its own Platform service client (`notification`), with its own SHA-256 secret hash and the narrow
`platform:replica:read` scope. It is never shared with Billing. Platform's side of the change is one
`platform.service-clients` list entry.

## Security

The authorization rules are per endpoint group, not one rule for the service. `SuiteEntitlementFilter` passes the
platform scope unconditionally and `@Authenticated` admits a machine, so a single blanket rule would either close the
one door the suite needs or open a third one onto every tenant endpoint.

| Group | Scope | Principal |
|---|---|---|
| `/api/v1/templates` | **platform only** | a user; the codes are platform-scoped and not grantable to a Tenant System |
| `/api/v1/settings`, `/api/v1/email-dispatches` | **tenant only** | a user; a platform caller has no tenant to configure and no ledger of its own here |
| `/api/v1/notifications*`, `/api/v1/notifications/preferences` | **either scope** | a user, at either scope: a Platform administrator has an inbox too |

- `TenantScopeAndUserGuard` enforces that table; a tenant scope calling a template endpoint is a 403, and so is a
  platform scope calling the settings or dispatch endpoints.
- `@Authenticated` also admits a Tenant System credential, and a machine has no inbox, so the self-service group
  refuses a non-user caller. The admin groups are additionally unreachable for a machine because no code is grantable
  to a Tenant System.
- Row-level security is on every tenant-owned table: the in-app rows, the settings, the preferences, the dispatch ledger
  and the outbox. A Platform user's rows are ordinary tenant rows under the reserved Platform tenant, so the same policy
  keeps them private. The replica tables are Platform-owned data and carry no RLS; each read port filters by tenant
  explicitly. `notification_templates` carries no RLS because it is shared content, which is why its endpoints are
  platform-only.
- The email command carries only the recipient, the subject and the bodies. `fromKey` is a configuration key, never an
  address; a tenant cannot make the suite send from an arbitrary domain.
- Mailer accepts an `email.send` for the Platform tenant. That is the narrow exception to its older "no platform-scope
  send" boundary, and it exists because a Platform administrator is a notification recipient; the command still has to
  pass schema validation and land in the same ledger.

## Dead-letter runbook

1. Inspect `notification.platform-events.dlq` in the broker UI: the payload, the `x-death` headers and the stack trace
   in the log identify the cause.
2. Fix the consumer or the data.
3. Shovel the messages back to `platform.events` with their original routing key; delete the shovel afterwards.
4. An event that is unprocessable by design is a fact, not a task: drop it and rebuild the replica if the state matters.

## Operations

- **Migration order:** `migrate`, then `api` and `worker`. Only the migrate step writes schema.
- **Platform Administrator's template codes.** The two template codes became platform-scoped, and Platform's bootstrap
  builds the `Platform Administrator` role from the platform-scope catalog **when it creates the role**. On an
  environment where that role already exists, a fresh boot does not rewrite it, so the role keeps the older code set and
  every template call answers 403. Re-grant once through the role API
  (`PUT /api/v1/roles/{id}/permissions` with the current `codesValidIn(platform)` set), or delete and let the bootstrap
  recreate it. A brand-new environment needs nothing.
- **Mailer's tenant registration.** `mailer_tenants` needs a row for the reserved Platform tenant, or a Platform user's
  email is accepted into mailer's ledger and never claimed. `V6__platform_tenant.sql` writes it, so a migrated database
  is correct; an environment that predates that migration is the one to check.
- **Keycloak:** a running realm needs the `notification-audience` mapper added through the admin API. The realm export
  only fixes a fresh environment.
- **Retention:** housekeeping deletes in-app rows past 90 days, redacts dispatch rows past 90 days (keeping the ledger
  row and its status), deletes inbox rows past 30 days and dead outbox rows past 7 days, in bounded batches per tenant.
  The sweep includes the reserved Platform tenant, so a Platform user's rows are retained on the same schedule and their
  queued email actually leaves the outbox.
- **End-to-end proof:** `ops/smoke/notification-smoke.sh` grants a user access, waits for the replica, asserts one
  Mailpit message and one in-app row, reads it and asserts `unreadCount` drops to zero — then repeats the whole check
  for a new Platform user at `platform.localhost`.
