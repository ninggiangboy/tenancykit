# TenancyKit Documentation

Documentation for the suite: the **Platform** service, the **Billing** app and the **Sample Tenant System** (a Cloud Resource Management reference tenant), each independently deployable. It is written for people and AI collaborators who need to know what the systems must do, why they are shaped this way, and where each rule is owned.

## Authority

- [system-specification.md](system-specification.md) is the original specification, kept as the historical baseline; it predates the suite split and is not edited further.
- The topic documents below carry the same requirements split by area, plus the decisions made after the specification. They are the maintained contract. If a topic document differs from the baseline, the topic document wins and the difference is a decision recorded in the ledger in [architecture.md](architecture.md).
- Once code exists, executable owners (API schemas, event schemas, database migrations, tests) take over the details they define. A topic document then keeps only the rationale and a link to that owner, and tables here that list events, fields or endpoints stop being the source of truth.

## Reading order

| Document | Owns |
|---|---|
| [overview-and-scope.md](overview-and-scope.md) | Purpose, vocabulary, who is responsible for what, non-goals |
| [architecture.md](architecture.md) | System diagram, decision ledger, module boundaries, technical principles |
| [platform-service.md](platform-service.md) | Platform's contexts, surface, token issuance, snapshot and streams |
| [suite-messaging.md](suite-messaging.md) | RabbitMQ topology, envelope, versioned replicas, rebuild, dead-letter handling |
| [mailer.md](mailer.md) | The delivery service: its command queue, the delivery status machine, the DLQ runbook |
| [mailer-tenant-onboarding.md](mailer-tenant-onboarding.md) | The trust policy a Tenant creates so mailer sends under the Tenant's own SES identity |
| [multi-tenancy-and-data-isolation.md](multi-tenancy-and-data-isolation.md) | Tenant model, isolation strategy, tenant resolution |
| [authentication-and-authorization.md](authentication-and-authorization.md) | Keycloak authentication, centralized RBAC in Platform, suite tokens, enforcement from the replica |
| [customers-and-tenant-mapping.md](customers-and-tenant-mapping.md) | Billing Customers and the Tenant User mapping |
| [pricing-and-usage.md](pricing-and-usage.md) | Pricing Engine, Usage Metering, Cost Estimation |
| [wallet-ledger-and-charging.md](wallet-ledger-and-charging.md) | Wallet, Ledger, Reservation, Charge, Prepaid and Postpaid |
| [invoicing-and-subscriptions.md](invoicing-and-subscriptions.md) | Invoice and Subscription |
| [payments-and-stripe.md](payments-and-stripe.md) | Payment Provider model, Stripe Direct and Connect, Payment, Refund, Stripe webhooks |
| [billing-session-and-portal.md](billing-session-and-portal.md) | Billing Session and the Customer Portal |
| [webhooks-and-events.md](webhooks-and-events.md) | Tenant System to Billing and Billing to Tenant System integration |
| [tenant-system.md](tenant-system.md) | The reference tenant, its scenarios and the Platform token flow |
| [security-and-reliability.md](security-and-reliability.md) | Security and operational requirements: token checks, broker users, DLQ runbook, key rotation |
| [concurrency-and-locking.md](concurrency-and-locking.md) | Lock order, coordination transactions, and the lock safety net |
| [runtime-profiles.md](runtime-profiles.md) | Which profile runs jobs and listeners, pools, probes and rollout order |

## Stateful records

These are dated records, not evergreen guidance:

- `plans/reports/brainstorm-261109-2031-core-billing-contract.md` captured the scope and the decisions that [architecture.md](architecture.md) now carries.
- `plans/reports/advise-261109-2227-tenant-system-auth.md` holds the confirmed machine-to-machine authentication decision and its rejected alternatives.
- `plans/reports/advise-261109-2248-ddd-clean-architecture.md` holds the confirmed DDD and Clean Architecture decisions, their rejected alternatives, and the enforcement plan.
- `plans/reports/advise-261109-2309-ledger-append-only.md` holds the confirmed append-only Ledger model, its correction and balance-projection rules, the wallet currency decision, and their rejected alternatives.
