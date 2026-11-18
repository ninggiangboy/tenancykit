# Mailroom

The suite's app for letting a **Tenant email its own customers**. A *mailroom* writes, addresses and dispatches the
mail; mailer is the carrier that delivers. It is a separate deployable (`backend/services/mailroom/`, root package `dev.ngb.mailroom`)
with three runtimes: `migrate`, `api` and `worker`.

A Tenant's system calls it with a suite token: it manages templates, its customers as receivers, groups and segments,
sends one email or a batch, runs a campaign and reads what happened to every message. Delivery does not live here.
`backend/services/mailroom/` renders an email and publishes the `email.send` command; [mailer](mailer.md) owns the transport, the retry and
the delivery truth, and reports the outcome back as the `email-delivery.changed` event.

## What it is, and what it is not

| It is | It is not |
|---|---|
| The owner of a tenant's templates, receivers, groups, segments, suppressions, sent-email ledger, campaigns and tracking events | A delivery service: `mailer` owns SMTP/SES, the retry budget and the delivery record |
| A producer of exactly one command, `email.send`, and a consumer of `email-delivery.changed` and `platform.events` | A producer of public events other apps subscribe to |
| A sender to a Tenant's own customers, with its own suppression and unsubscribe rules | The suite's notification app, which emails Platform users; see [notification.md](notification.md) |
| A REST API a Tenant System drives directly | A console UI: the UI is a separate follow-up plan |

The dated reversal in [overview-and-scope.md](overview-and-scope.md) records why this app exists: the recipient is the
Tenant's customer, the data belongs to the Tenant, and the suite stores it on the Tenant's behalf under its retention
and deletion rules. It reverses the two older non-goals "notifying a Tenant's customers" and "bulk/marketing
campaigns".

## Runtimes

| Profile | Runs | Migrates |
|---|---|---|
| `api` | HTTP: templates, receivers, groups, segments, suppressions, single and batch email, campaigns, the public tracking endpoints, tenant settings | no |
| `worker` | The `platform.events` and `mailroom.mailer-events` listeners, the outbox relay, the replica rebuild, the campaign scheduler and publisher, housekeeping | no |
| `migrate` | Flyway as the table owner, then exits | yes |
| default (no profile) | `api` and `worker` together, for dev and tests | yes |

Both long-running runtimes include `replicaState` in the readiness group. The worker is the only runtime that rebuilds
the replica, so on a fresh database `api` stays `DOWN` until the worker has finished one full rebuild.

## Contexts

Four contexts, each mapped to one directory under `contexts/`:

| Context | Owns |
|---|---|
| `platform` | A replica of the Platform data it enforces: `tenants`, `tenant_domains`, `tenant_systems`, `user_grants`, `app_entitlements`, plus the read ports `TenantDirectory` and `UserDirectory`. Nothing else reaches these tables |
| `content` | Templates, immutable revisions, the policy that refuses hostile constructs, and the sandboxed renderer |
| `audience` | Receivers, groups and memberships, suppressions, the send-eligibility answer, and segments |
| `sending` | The sent-email ledger, the batch and campaign publisher, the delivery-result consumer, tracking tokens and events, and campaign statistics |

`sending` reaches `content` and `audience` only through their `api` ports. ArchUnit fails the build when a context names
another context's tables, and the replica tables stay in `platform` so `context-uses-only-own-tables` holds while the
four contexts share one database.

## Broker items

| Item | Value |
|---|---|
| Consumes | `platform.events` on `mailroom.platform-events` (the five state-transfer keys), and `mailer.events` on `mailroom.mailer-events` with routing key `email-delivery.changed` |
| Dead-letters | to `suite.dlx`, drained by `mailroom.platform-events.dlq` and `mailroom.mailer-events.dlq` |
| Publishes | `email.send` to `mailer.commands` through the transactional outbox |
| Declares | no exchange of its own: `email.send` is a command into mailer's exchange, and mailer owns `mailer.events` |

Every message uses envelope `schemaVersion` 2.

## Data and status machines

### Templates

`email_templates` holds the current pointer and `email_template_revisions` holds immutable revisions. A campaign pins a
revision, and every email stores the revision it rendered, so editing a template never changes history. Rendering uses
JMustache inside a sandbox:

- partials, lambdas and unescaped output (`{{{ }}}`, `{{& }}`) are refused at write and again at load;
- unescaped output is allowed in the text part only, because text is not markup;
- limits: section depth 4, 256 KiB per part, 262144 characters of rendered output, 10,000 total loop iterations and
  500 ms wall clock per render;
- every variable is strict at every depth, including `receiver.*`: an unknown variable fails the render, the email is
  `FAILED` with the variable name, and nothing is sent.

### Emails

`TRANSACTIONAL` (the API) and `MARKETING` (a campaign) are the two kinds. The status machine is:

```
QUEUED ──mailer SUCCEEDED──▶ SENT     (terminal)
   │
   ├────mailer DEAD────────▶ FAILED   (terminal)
   └────eligibility or render refused──▶ SKIPPED / FAILED (never published)
```

The result consumer matches by `(tenant_id, requestId)`, applies only a `completedAt` newer than the one already
recorded, maps `SUCCEEDED` to `SENT` and `DEAD` to `FAILED`, and ignores a `status` it does not know. A delivery mailer
declares `DEAD` after a lost worker's final attempt reaches the same branch as any other terminal failure, so an email
never stays `QUEUED` because its worker died mid-send.

An inline body may carry `html`, `text` or both: a text-only email renders a null HTML part instead of failing, which is
what the command contract allows. A batch item may carry its own `idempotencyKey`, so retrying a whole batch after a
timeout does not resend every item; an item whose `kind` is missing or unknown is reported as that item's error rather
than failing the whole call.

### Campaigns

```
DRAFT ──schedule──▶ SCHEDULED ──due or send-now──▶ SENDING ──all published──▶ COMPLETED
  │                   │  ▲                            │  ▲
  │                   │  └───── resume ──────── PAUSED ◀┘  │
  └─────cancel────────┴───────────────────────────cancel┴──▶ CANCELLED
```

`COMPLETED` and `CANCELLED` are terminal. Pausing stops the worker taking more batches; it never withdraws a command
already published. The audience is snapshotted once, in pages, when the campaign enters `SENDING`; eligibility is
re-checked at publish time, so a receiver who unsubscribes after the snapshot is skipped.

The publish rate is the tenant's, not each campaign's: `campaign_publish_per_minute` is divided across the ticks in a
minute and that shared budget is spent across every `SENDING` campaign, so a tenant with N campaigns does not get N
times the ceiling that protects mailer and the transport. Each campaign starts and each recipient is published in its
own transaction, so one unstartable campaign (its segment was deleted) or one failing recipient is reported and skipped
instead of rolling back the tick — a single transaction would re-run the same failure every tick and hold the tenant's
other campaigns behind it.

### Suppression and unsubscribe

| Scope | Written by | Blocks |
|---|---|---|
| `MARKETING` | an unsubscribe, or a manual marketing block | marketing only |
| `ALL` | a manual block | marketing and transactional |

An unsubscribe never blocks a password-reset mail. `ALL` is the Tenant's explicit choice. A receiver deletion is a hard
delete that removes memberships and keeps the suppression row, so a deleted-then-re-imported address is still not
mailed.

### Tracking

Marketing mail carries a 1×1 open pixel, links rewritten at render time to `/mailroom/t/c/{token}`, and a one-click
unsubscribe link (`List-Unsubscribe` plus `List-Unsubscribe-Post`). Tokens are HMAC-signed and authorise one action on
one email. An open means "the pixel was loaded": a client that prefetches (Apple Mail Privacy Protection) inflates the
count, so `opened` counts distinct emails and `open_events` counts total loads. A click redirects only to a URL recorded
at render time, so the suite domain cannot become an open redirect.

The unsubscribe link in a body is never rewritten. It must keep working after the body is purged — housekeeping also
removes the recorded links — and rewriting it would both count an unsubscribe as a click and turn the footer into
"link expired" once that purge has run. A click destination the link column cannot store (over 4096 characters, or not
a valid URI) is left in place untracked rather than aborting the send, and the recorded destination is the unescaped
URL, so a query string survives with its real `&`.

Public endpoints (`/mailroom/t/...`) bypass the suite-token and entitlement filters; the tenant scope comes from the
verified token alone. An invalid token returns the same response a valid one does, so nothing is learned from the
status code. The controller maps both `/t/...` and `/mailroom/t/...`: the compose proxy strips the prefix, while the ALB
forwards the path unchanged, and serving both is what keeps a pixel, a click and an unsubscribe alive in either
runtime.

### Segments

A segment is a closed, typed filter over a receiver's attributes and three built-in fields, validated when it is stored
and compiled to parameterised SQL when it is evaluated. Equality and membership compile to JSONB containment
(`attributes @> jsonb_build_object(key, to_jsonb(value))`), which the `jsonb_path_ops` GIN index answers and which is
type-exact: the number `3` and the string `"3"` are different values. Ordering compares a number, and the cast is
guarded by `jsonb_typeof(attributes -> key) = 'number'`, so one receiver holding `age = "unknown"` does not abort the whole
query — it simply does not match. The validator refuses an ordering operator on a built-in text field, because the
compiler could never execute it.

### Retention

Rendered bodies (`body_html`, `body_text`) and tracking links are removed after `body_retention_days` (30 by default);
metadata, events and statistics remain, and `GET /emails/{id}` then answers `bodyPurged: true`. The housekeeping job
also trims `event_inbox` and relayed `outbox` rows the way notification does. No address, body or token is ever
logged.

## API surface

Every tenant endpoint is under `/mailroom/api/v1` on the client's side (the proxy strips `/mailroom`, and the
controller serves `/api/v1`). JSON field names are camelCase, as in every other app; the database columns are snake_case
and never appear on the wire.

| Resource | Endpoints |
|---|---|
| Templates | `GET/POST /templates`, `GET/PUT/DELETE /templates/{id}`, `GET /templates/{id}/revisions`, `POST /templates/{id}/preview` |
| Receivers | `GET/PUT /receivers`, `GET/PATCH/DELETE /receivers/{id}`, `POST /receivers/bulk` |
| Groups | `GET/POST /groups`, `GET/PUT/DELETE /groups/{id}`, `GET/POST/DELETE /groups/{id}/members` |
| Suppressions | `GET/POST/DELETE /suppressions` |
| Segments | `GET/POST /segments`, `GET/PUT/DELETE /segments/{id}`, `POST /segments/{id}/preview` |
| Emails | `GET/POST /emails`, `POST /emails/batch`, `GET /emails/{id}` |
| Campaigns | `GET/POST /campaigns`, `GET/PUT /campaigns/{id}`, `POST /campaigns/{id}/schedule|send|pause|resume|cancel|test`, `GET /campaigns/{id}/stats` |
| Settings | `GET/PUT /settings` |
| Public | `GET /mailroom/t/o/{token}.gif`, `GET /mailroom/t/c/{token}`, `GET/POST /mailroom/t/u/{token}` |

`PUT` and a campaign's `schedule` answer an `ETag` and accept `If-Match`; a stale tag is `412` with the current tag in
the header. A duplicate template key, group name or segment name is `409`. An illegal campaign transition is `409`. A
missing resource in the caller's tenant is `404`. Every path is tenant-scoped, so a Platform token is refused.

## Configuration

| Variable | Meaning |
|---|---|
| `MAILROOM_DB_URL`, `MAILROOM_DB_USER`, `MAILROOM_DB_PASSWORD` | The application connection; `mailroom_app` owns nothing and row-level security applies to it |
| `MAILROOM_MIGRATION_DB_USER`, `MAILROOM_MIGRATION_DB_PASSWORD` | Flyway, as the table owner |
| `MAILROOM_RABBITMQ_HOST`, `MAILROOM_RABBITMQ_USER`, `MAILROOM_RABBITMQ_PASSWORD` | The broker user: write `mailer.commands` and its own queues, read its own queues and the two source exchanges, nothing else |
| `MAILROOM_OIDC_ISSUER_URI`, `MAILROOM_OIDC_JWK_SET_URI` | The user-token issuer; the suite token is verified against Platform's JWKS |
| `MAILROOM_PLATFORM_BASE_URL`, `MAILROOM_PLATFORM_ISSUER`, `MAILROOM_PLATFORM_JWK_SET_URI` | The replica's snapshot source and token verifier |
| `PLATFORM_MAILROOM_SERVICE_CLIENT_ID`, `PLATFORM_MAILROOM_SERVICE_CLIENT_SECRET` | The replica's own client; only its SHA-256 is configured in Platform |
| `MAILROOM_DEFAULT_FROM_KEY`, `MAILROOM_DEFAULT_FROM_NAME`, `MAILROOM_DEFAULT_REPLY_TO` | What a tenant inherits until it saves `/settings` |
| `MAILROOM_DEFAULT_BODY_RETENTION_DAYS` (30), `MAILROOM_DEFAULT_CAMPAIGN_PUBLISH_PER_MINUTE` (600) | The retention and the campaign rate before a tenant overrides them |
| `MAILROOM_TRACKING_KEY_ID`, `MAILROOM_TRACKING_SECRET`, `MAILROOM_PUBLIC_BASE_URL` | The HMAC key that signs tracking tokens and the public prefix a recipient reaches. There is no default secret: an empty `MAILROOM_TRACKING_SECRET` fails startup rather than signing every link with a value anyone can read |
| `MAILROOM_JOBS_ENABLED`, `MAILROOM_HOUSEKEEPING_ENABLED` | Switches; neither drops queued work |

A tracking key rotates without breaking mail already sent: add a second entry under `mailroom.tracking.keys` in the
service configuration, make the new key id `active-key-id`, and keep the old one until the mail that carries it is no
longer read. Every configured key still verifies; removing one invalidates only its tokens.

## Runbook

| Symptom | Check |
|---|---|
| A campaign stays `SCHEDULED` | The worker is running with `MAILROOM_JOBS_ENABLED=true`; the *CampaignSchedulerJob* logs a count per tenant each tick |
| A campaign stays `SENDING` with `campaign_recipients` in `PENDING` | The *CampaignPublisherJob* is running; a claim lease is 60s, so a crashed worker's rows are taken over on a later tick. A row whose own transaction failed keeps its claim and returns the same way |
| The service refuses to start with "must define a non-blank k1" | `MAILROOM_TRACKING_SECRET` is unset. There is deliberately no default; a well-known key would let anyone mint a token for any email in any tenant |
| A tracking link 404s in a deployment | Check which runtime serves it: the controller answers `/t/...` and `/mailroom/t/...`, so a 404 means the ALB rule or the proxy route, not the app |
| A recipient never receives mail | `GET /emails?recipient=...` shows `SKIPPED` (suppression) or the `statusReason`; mailer's ledger remains the delivery truth |
| Delivery outcomes stop arriving | `mailroom.mailer-events` has a consumer; the worker logs an event that exhausted its retries and the message lands in `mailroom.mailer-events.dlq` |
| An open or click is not counted | The token verifies with the configured keys; events are capped at 60 per email per minute; `bodyPurged: true` means the link was removed by retention |
| A template edit is refused | The sandbox violation is named in the message; an `ETag` mismatch is `412` |

Nothing in the log contains an address, a body or a token. The four public paths answer the same way for a valid and
an invalid token, except that a valid one records the event or redirects.

## Related

Delivery, the command contract and the delivery status machine: [mailer.md](mailer.md). The broker and the envelope:
[suite-messaging.md](suite-messaging.md). The permission catalog and the command schema:
[contracts/permissions/mailroom.yaml](../contracts/permissions/mailroom.yaml) and
[contracts/commands/email.send.schema.json](../contracts/commands/email.send.schema.json).
