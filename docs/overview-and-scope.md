# Overview and Scope

## Purpose

The repository is the foundation of a multi-tenant SaaS suite: one **Platform** owns identity, tenancy, access and
app entitlement, and every **app** of the suite owns its own business data and enforces the access it was granted.
Billing is the first app; the sample tenant is a complete integration reference. Many Tenants share the one Platform
while keeping independent data, accounts, integration configuration and billing operations.

The goal is that a new business app (CRM, HR, …) joins the suite by adding a build, a permission catalog, an
`AppCode` and a proxy route, with Platform and Billing unchanged.

## Four deployables

| System | Owns | Reached at |
|---|---|---|
| **Platform** (`platform/`) | Tenants, tenant domains, users, centralized RBAC, app entitlement, Tenant Systems, API credentials and the suite's signed tokens | `platform.<base-domain>` |
| **Billing** (`billing/`) | Pricing, Wallet, Ledger, Reservation, Charge, Invoice, Payment, Refund, Subscription, Webhook, the Billing Session and the Customer Portal. It keeps a **replica** of the Platform data it enforces and owns no tenant, user or role logic | `<tenant-code>.<base-domain>` |
| **Notification** (`notification/`) | Notification types, suite-wide templates, locale and user preferences, the stored in-app notification list, the email dispatch ledger and the outbox that produces `email.send`. It keeps the Platform replica, notifies the suite's own users (tenant users and Platform administrators), and reads no other app's business data; delivery stays with `mailer/` | `<tenant-code>.<base-domain>` and `platform.<base-domain>`, through the `/notification` prefix |
| **Sample Tenant System** (`tenant/`) | Its own users, catalog, orders, usage and the policy it applies to Billing's state changes. It proves the integration and deploys without Billing | its own host |

`notification/` is the suite's app for **notifying the suite's own users** about platform-side state changes. It
owns the notification types, the suite-wide templates, locale and user preferences and the stored in-app
notification list; it does not notify a Tenant's customers and reads no business data from another app; email
delivery stays with `mailer/`. Templates are the suite's content rather than a tenant's: one row serves every tenant and
only a Platform user at platform scope may edit it, so a tenant administrator has no surface for rewriting an email.
This supersedes the cancelled phase 10 of `261112-0726-enterprise-suite-foundation`
(the dated reversal, 2026-11-15, moves notification from a non-goal to a deployable because recipients are the suite's
own users, so no business data crosses a boundary).

They are separate Gradle builds, separate databases and separate processes. They meet three ways, never by sharing
code:

- **Signed tokens.** Platform issues a short-lived JWT; Billing verifies it and authorizes from the `scope` codes
  and the tenant it names.
- **RabbitMQ.** One topic exchange per producer. Platform publishes the state-transfer `*.changed` events every
  consumer replicates; Billing publishes its own public events on `billing.events`.
- **The snapshot API.** A consumer rebuilds its replica from zero by paging Platform's snapshot streams, so a fresh
  app starts correct without replaying history.

Webhooks stay in Billing: a Tenant System still registers an endpoint with Billing and verifies Billing's signed
deliveries.

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
- Notifying a Tenant's **customers**. The suite's notification app serves Platform tenant users only: a Customer,
  payment or invoice notification belongs to the Tenant System that owns that relationship. The notification app reads
  no Billing data and keeps no customer contact.
- SMS, push, Slack, Telegram or a real-time SSE stream; a tenant-configurable rules engine, a template editor, or
  bulk/marketing campaigns; attachments, bounce and complaint processing, digest scheduling, or a second email retry
  loop. Email **delivery** stays with `mailer/`, which sends an email a producer has already rendered and owns no
  template, no rule about who is notified and no localisation. Webhooks stay in Billing.
- A second **business app** beyond Billing in the sense of an app that owns commercial data. The suite's first app
  beyond Billing is `notification/`, a platform-facing app rather than a business app; the extension trace — a build, a
  permission catalog, an entitlement and a proxy route, with no Platform code change — is now proven rather than
  hypothesised.
- Charging tenants for suite use.
- An API gateway with logic. The reverse proxy routes on the Host header only.
- CDC or event sourcing. A consumer rebuilds by paging the snapshot API.
- Migrating existing data from an earlier layout.
- The Suite Console UI, which is a separate follow-up plan.
- A first business app beyond Billing; the extension trace is proven with a future app.
