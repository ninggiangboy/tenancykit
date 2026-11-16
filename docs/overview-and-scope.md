# Overview and Scope

## Purpose

The repository is the foundation of a multi-tenant SaaS suite: one **Platform** owns identity, tenancy, access and
app entitlement, and every **app** of the suite owns its own business data and enforces the access it was granted.
Billing is the first app; the sample tenant is a complete integration reference. Many Tenants share the one Platform
while keeping independent data, accounts, integration configuration and billing operations.

The goal is that a new business app (CRM, HR, …) joins the suite by adding a build, a permission catalog, an
`AppCode` and a proxy route, with Platform and Billing unchanged.

## Seven deployables

| System | Owns | Reached at |
|---|---|---|
| **Platform** (`platform/`) | Tenants, tenant domains, users, centralized RBAC, app entitlement, Tenant Systems, API credentials and the suite's signed tokens | `platform.<base-domain>` |
| **Billing** (`billing/`) | Pricing, Wallet, Ledger, Reservation, Charge, Invoice, Payment, Refund, Subscription, Webhook, the Billing Session and the Customer Portal. It keeps a **replica** of the Platform data it enforces and owns no tenant, user or role logic | `<tenant-code>.<base-domain>` |
| **Notification** (`notification/`) | Notification types, suite-wide templates, locale and user preferences, the stored in-app notification list, the email dispatch ledger and the outbox that produces `email.send`. It keeps the Platform replica, notifies the suite's own users (tenant users and Platform administrators), and reads no other app's business data; delivery stays with `mailer/` | `<tenant-code>.<base-domain>` and `platform.<base-domain>`, through the `/notification` prefix |
| **Mailroom** (`mailroom/`) | A Tenant's templates, receivers, groups, segments, suppressions, the sent-email ledger, campaigns and tracking events for the Tenant's **own customers**. It keeps the Platform replica, renders with a sandboxed Mustache and publishes `email.send`; delivery stays with `mailer/`, which reports the outcome back on `mailer.events` | `<tenant-code>.<base-domain>` through the `/mailroom` prefix; `/mailroom/t/*` is public for recipients |
| **Survey** (`survey/`) | A Tenant's surveys, their immutable versions, links, visits, page events, responses, answers, uploads and the analysis of all of it, including the one expression language that decides branching, computed variables, scoring and piping. It keeps the Platform replica, consumes no business data from another app and sends nothing: it issues links and the Tenant places them | `<tenant-code>.<base-domain>` through the `/survey` prefix; `/survey/p/*` is public for respondents |
| **Attribution** (`attribution/`) | A Tenant's campaigns and tracked links, the clicks, page views, sessions and conversions they produce, the credit each click receives under a per-campaign model, and the advertising cost behind it, entered by hand or imported from Google Ads and Meta Ads. It keeps the Platform replica, writes `attribution.ingest` for its own worker, and reads no other app's business data | `<tenant-code>.<base-domain>` through the `/attribution` prefix; `/go/*` is the public redirect and `/t/*` the public collection surface |
| **Sample Tenant System** (`tenant/`) | Its own users, catalog, orders, usage and the policy it applies to Billing's state changes. It proves the integration and deploys without Billing | its own host |

`notification/` is the suite's app for **notifying the suite's own users** about platform-side state changes. It
owns the notification types, the suite-wide templates, locale and user preferences and the stored in-app
notification list; it does not notify a Tenant's customers and reads no business data from another app; email
delivery stays with `mailer/`. Templates are the suite's content rather than a tenant's: one row serves every tenant and
only a Platform user at platform scope may edit it, so a tenant administrator has no surface for rewriting an email.
This supersedes the cancelled phase 10 of `261112-0726-enterprise-suite-foundation`
(the dated reversal, 2026-11-15, moves notification from a non-goal to a deployable because recipients are the suite's
own users, so no business data crosses a boundary).

`mailroom/` is the suite's app for letting a **Tenant email its own customers**. It owns that tenant's templates,
receivers, groups, segments, suppressions, sent-email ledger, campaigns and tracking events; it keeps the Platform
replica, renders with a sandboxed Mustache and publishes `email.send`, while delivery stays with `mailer/`. Unlike
notification, it is tenant-facing and stores personal data of the Tenant's customers, so it has its own database and
its own retention and deletion rules. Its own customers are the Tenant's, not the suite's: the data is stored on the
Tenant's behalf, never read by Platform or another app, and a hard delete removes the customer while keeping the
suppression that says the address must not be mailed again. It is `mailroom/` and not a module of `notification/`
because the two have different recipients, different data-protection obligations and different runtimes.

The dated reversal of 2026-11-15 (recorded with the mailroom app plan) moves "notifying a Tenant's customers" and
"bulk/marketing campaigns" from non-goals to scope: the recipient is the Tenant's customer, the data is the Tenant's
own, and the suite stores it on the Tenant's behalf under explicit retention and deletion rules. Everything else in
those two non-goal bullets still stands — no SMS, push, Slack, Telegram or real-time stream; no tenant-configurable
rules engine or template editor; no attachments, bounce/complaint processing, digest scheduling or second retry loop.

`survey/` is the suite's app for **asking a Tenant's own customers something**. It owns a Tenant's surveys, their
immutable versions, the links that reach a respondent, the visits those links produce, the responses and the analysis
of all of it. It never sends a message and knows no channel: it issues links, and the Tenant places one wherever it
likes — a mailroom campaign, a QR code, a page on its own site. Delivery therefore stays with `mailroom/` and
`mailer/`, and Survey publishes no `email.send`. Its data is the Tenant's customer data on the Tenant's behalf, so it
has its own database, its own retention and deletion rules, and no raw IP is stored. It is API only: the builder UI
and the fill UI are a later plan.

`attribution/` is the suite's app for **telling a Tenant which marketing link, channel or ad brought a customer, and
what that cost**. It owns the campaigns, the tracked links that redirect through `<tenant-code>.<base-domain>/go/<slug>`,
the clicks, page views, sessions and conversions those links produce, the credit each click receives under a
per-campaign model, and the advertising spend behind it. It reads no business data from another app and calls no other
app; its ad-platform connections are read-only. It measures only traffic that arrived through a tracked link, which is
why it is an attribution product rather than a web-analytics one, and it stores no raw IP and sets no cookie before
consent. It counts as a deployable in the same sense as Survey: a build, a permission catalog, an entitlement and a
proxy route, with Platform, Billing, mailroom and the shared resolver untouched.

They are separate Gradle builds, separate databases and separate processes. They meet three ways, never by sharing
code:

- **Signed tokens.** Platform issues a short-lived JWT; Billing verifies it and authorizes from the `scope` codes
  and the tenant it names.
- **RabbitMQ.** One topic exchange per producer. Platform publishes the state-transfer `*.changed` events every
  consumer replicates; Billing publishes its own public events on `billing.events`.
- **The snapshot API.** A consumer rebuilds its replica from zero by paging Platform's snapshot streams, so a fresh
  app starts correct without replaying history.

Webhooks stay in the app that owns the fact: a Tenant System registers an endpoint with Billing and verifies
Billing's signed deliveries, and Survey does the same with its own `Survey-*` deliveries. The signing, destination
policy, guarded DNS resolution, HTTP send and retry schedule are the shared `core-webhook` mechanics; the endpoints,
the delivery state and the event catalog stay in the app.

## Vocabulary

| Term | Meaning |
|---|---|
| Platform | The service that owns tenants, users, access, entitlement and the suite's tokens |
| App | One business application of the suite (`billing`, and later `crm`, …); it owns its data and enforces `platform`-granted permissions |
| Tenant | A business space of the suite, with its own data, users and entitlements; identified by a unique `tenant-code` |
| Tenant System | A Tenant's business system that calls an app over REST with a suite token |
| Tenant User | A person who belongs to a Tenant, authenticated by Keycloak and authorized by Platform |
| Platform Administrator | A person who administers the Platform itself (`platform` scope) |
| Suite token | A Platform-signed JWT for one app (`aud`), carrying the caller's permission codes for that app |
| Replica | An app's local copy of Platform data (tenants, domains, systems, user grants, entitlements), rebuilt from the snapshot and kept current by `platform.events` |
| Keycloak | The Identity Provider; authentication only |
| Stripe / Stripe Connect | The Payment Provider, and the mechanism that links a Tenant's Stripe account to the Platform |
| Campaign | A Tenant's marketing effort with one attribution model, one window and one currency; tracked links belong to it |
| Tracked link | A link with a unique slug that redirects through the suite's domain, appends a signed click id and records the click |
| Click id | The signed token a redirect appends to the destination; only an id the app issued is accepted by collection and the postback |
| Conversion | A goal reached, with a value and a currency, reported by a postback or the site snippet |
| Attribution model | `FIRST`, `LAST` or `LINEAR`: how a conversion's value is divided among the clicks in its chain |
| Credit | The share of a conversion's value one click receives under the owning campaign's model |
| Coupon | The discount a Tenant defines: one benefit (percent off, fixed amount off or a one-time wallet credit), one duration and one scope, with immutable terms |
| Promotion code | A code that redeems one coupon, with its own limits and validity |
| Redemption | One coupon redeemed onto one wallet, with the duration it still has left |

## Functions

Billing provides the Pricing Engine, Usage Metering, Cost Estimation, Wallet and Ledger, Reservation, Charge,
Invoice, Payment, Refund, Subscription, Billing Session, Webhook and per-Tenant Payment Provider Configuration.
Details live in the area documents listed in the [index](README.md). Supported pricing models are time-based,
usage-based, workload-based, event-based, subscription and hybrid; both Prepaid and Postpaid are supported.

## Who is responsible for what

**Platform**
- Manages Tenants, tenant domains, users, RBAC (roles, permissions, assignments and direct grants) and app
  entitlement.
- Authenticates users through Keycloak and resolves their permissions.
- Issues the suite's tokens: service clients and Tenant Systems get one at `POST /oauth/token`.
- Publishes the state-transfer events every app replicates.

**Billing**
- Owns pricing, cost calculation and estimation, balance checks, budget holds and deductions.
- Records Usage and owns every financial transaction: Wallet, Ledger, Reservation, Charge, Invoice, Payment,
  Refund, Subscription.
- Manages Payment Provider Configuration per Tenant, including Stripe Direct and Stripe Connect.
- Provides the REST API for Tenant Systems, including the API that creates Billing Sessions.
- **Enforces** `billing:*` permissions from its replica of Platform data; it never mints, edits or decides them.
- Guarantees financial consistency, Idempotency and auditability.
- Serves the Customer Billing Portal.

**Survey**
- Owns a Tenant's surveys, their immutable versions, links, visits, page events, responses, answers, uploads and the
  analysis of all of it.
- Owns the one expression language used for branching, computed variables, quiz scoring, piping and analytics
  filters, and publishes its schema and test vectors in `contracts/survey/` so a future fill UI agrees with it.
- Enforces `survey:*` permissions from its replica of Platform data; it never mints, edits or decides them.
- Issues links and sends nothing; a Tenant reaches a respondent through mailroom.

**Attribution**
- Owns a Tenant's campaigns, tracked links, destinations and rules, and the clicks, page views, sessions and
  conversions they produce.
- Owns the touchpoint chain of each conversion and the credit each click receives under the owning campaign's model,
  reproducibly from a stored chain.
- Owns advertising cost, entered by hand or imported read-only from Google Ads and Meta Ads, and the cost metrics
  derived from it.
- Enforces `attribution:*` permissions from its replica of Platform data; it never mints, edits or decides them.
- Stores no raw IP, sets no cookie before consent, and provides an erasure endpoint keyed by visitor id.

**Sample Tenant System**
- Manages its own users, business roles and permissions.
- Manages Customers and their mapping to Billing Customers.
- Owns resources, orders, execution, the resource lifecycle, the execution queue and background workloads.
- Exchanges its Platform client credentials for a suite token and calls Billing with it.
- Decides whether to execute, renew, suspend or stop the service, and defines its own policy for Billing state
  changes.

**Division rules**
- Keycloak authenticates; Platform authorizes; apps only enforce.
- No shared database. Shared code lives only in `core/`; contracts live in `contracts/`.
- An app never operates on another app's resources; the sample tenant controls its own resource lifecycle.
- Billing publishes state and results through API, Events and Webhooks. It never imposes actions on the Tenant
  System, which decides its own next step.
- Stripe processes payment transactions; Billing synchronizes status and updates the financial ledger.
- Each Tenant has an independent Stripe Account Configuration.
- A Customer can act only within the scope of the Billing Session issued to them.
- Tenants are fully isolated in data and access rights; so are apps.

## Non-goals

- Payment providers other than Stripe. Only the extension seam is designed.
- Custom domains. Only the domain-mapping seam is designed.
- Platform or an app managing a Tenant System's own users, passwords, roles or permissions.
- Billing operating on resources or deciding on a Tenant System's behalf.
- **Reversed (2026-11-15):** notifying a Tenant's **customers**. `mailroom/` now serves them, under the decisions recorded
  in [mailroom.md](mailroom.md): the recipient is the Tenant's customer, the data is the Tenant's own, and the suite stores it
  on the Tenant's behalf with retention and deletion rules. The notification app still serves Platform tenant users
  only and reads no Billing data.
- SMS, push, Slack, Telegram or a real-time SSE stream; a tenant-configurable rules engine, a template editor;
  attachments, bounce and complaint processing, digest scheduling, or a second email retry loop. Email **delivery**
  stays with `mailer/`, which sends an email a producer has already rendered and owns no template, no rule about who is
  notified and no localisation. Webhooks stay in Billing. **Reversed (2026-11-15):** bulk/marketing campaigns are now in
  scope for `mailroom/`, which owns the campaign state machine, suppression and tracking; mailer still owns delivery.
- A second **business app** beyond Billing in the sense of an app that owns commercial data. The suite's first app
  beyond Billing is `notification/`, a platform-facing app rather than a business app; the extension trace — a build, a
  permission catalog, an entitlement and a proxy route, with no Platform code change — is now proven rather than
  hypothesised. **Reversed (2026-11-16):** `survey/` is the second tenant-facing business app beyond Billing and the
  first one that is not about money or mail. It joins the suite the same way, proving the trace a second time from a
  different direction: it publishes its own events and signed webhooks, and Platform, mailroom and the shared resolver
  are untouched.
- A builder UI or a fill UI for `survey/`; sending invitations or reminders from Survey; reconciling a respondent with
  a mailroom receiver; CAPTCHA, virus scanning, realtime co-editing, and a retention schedule for responses (only an
  explicit deletion). A Tenant that must not keep an answer deletes the response.
- `attribution/` measuring traffic that did not arrive through a tracked link, and any claim to be a general web
  analytics product. It also does not create or edit ads on any ad platform, serve a custom domain, store a raw IP,
  resolve one person across devices, fingerprint, stream in real time, convert currency, bucket reports in a time zone
  other than UTC, or export to object storage (CSV is streamed).
- Charging tenants for suite use.
- An API gateway with logic. The reverse proxy routes on the Host header only.
- CDC or event sourcing. A consumer rebuilds by paging the snapshot API.
- Migrating existing data from an earlier layout.
- The Suite Console UI, which is a separate follow-up plan.
- A first business app beyond Billing; the extension trace is proven a second time with `survey/`.
