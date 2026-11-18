---
title: "Enterprise suite foundation: Platform service and RabbitMQ"
description: "Turn the repo into the foundation of a multi-tenant SaaS suite: extract Platform from Billing and connect the services through RabbitMQ."
status: completed
priority: P1
effort: 29d
branch: dev
tags: [refactor, backend, auth, infra, api, database]
blockedBy: []
blocks: []
created: 2026-11-12
---

# Enterprise suite foundation

## Overview

The repo stops being "Billing plus a sample tenant" and becomes the foundation of a multi-tenant SaaS suite (learning/portfolio goal). Platform (tenant, domain, user, centralized RBAC, app entitlement, machine credentials and suite tokens) is extracted from `billing/contexts/platform` into its own deployable `platform/` build. Events cross systems through RabbitMQ. Billing becomes one app of the suite and keeps local replicas of the Platform data it needs; it keeps sending its own outbound webhooks.

**Scope change, 2026-11-12:** the Notification Hub (webhook move, email with templates, in-app notifications) is dropped. Phases 9, 10 and 11 are cancelled, phases 12-14 are reduced, and the Hub's contracts, audience and catalog were removed from `dev`. The Hub mentions in the Done phases 1-8 are left as the record of what was done at the time.

Source: `/ak-advise` session of 2026-11-12. Preview: [suite preview](../reports/advise-261112-1400-enterprise-suite-pivot-preview.html).

## Done means

- `docker compose up` runs Keycloak, RabbitMQ, Platform, Billing and the sample tenant, each service with its own database.
- Creating a tenant in Platform makes its replica appear in Billing (integration tests per service and the suite smoke script).
- Platform exposes a snapshot API; the shared replica consumer in `core/` rebuilds a replica from zero.
- Billing has no tenant, user or role business logic: `billing/contexts/platform` is deleted, the identity tables and the bearer-secret path are gone, cross-cutting code lives in `core/`.
- `tenant/` calls Billing with a Platform-issued JWT and still verifies Billing's webhooks.
- ArchUnit, Gradle and database-level checks block any service from reaching another service's database.
- Docs describe the suite model.

## Out of scope

Business apps (CRM, HR...), a Notification Hub or any email, in-app, SMS or push channel, moving webhooks out of Billing, charging tenants for suite use, payment providers other than Stripe, custom domains, an API gateway with logic, CDC or event sourcing, migrating existing data, the Suite Console UI (delivered by `plans/261117-1456-suite-console`; see [docs/console.md](../../docs/console.md)).

## Constraints

- Keycloak authenticates only; Platform authorizes; apps only enforce.
- No shared database. Shared code lives only in `core/`; contracts live in `contracts/`.
- Keep the conventions: `api`/`domain`/`application`/`adapter` source sets, jOOQ with committed generated code, ArchUnit with arch-fixtures, `UnitOfWork`.
- RabbitMQ: one topic exchange per producer (`platform.events`, `billing.events`), one queue per consuming app with a dead-letter exchange. `billing.events` has no consumer for now.
- Replicated events carry a monotonic `version`; consumers drop older versions.
- Every phase merges into `dev` on its own with every build green.

## Phases

| # | Phase | Effort | Depends on | Status |
|---|-------|--------|-----------|--------|
| 1 | [Cross-cutting code to core](./phase-01-cross-cutting-to-core.md) | 2d | none | Done |
| 2 | [Suite contracts](./phase-02-suite-contracts.md) | 1d | 1 | Done |
| 3 | [RabbitMQ messaging](./phase-03-rabbitmq-messaging.md) | 3d | 1, 2 | Done |
| 4 | [Platform: tenancy, entitlement, suite tokens](./phase-04-platform-tenancy-and-entitlement.md) | 5d | 3 | Done |
| 5 | [Platform: users and centralized RBAC](./phase-05-platform-users-and-rbac.md) | 4d | 4 | Done |
| 6 | [Platform: tenant systems and M2M tokens](./phase-06-platform-tenant-systems-and-tokens.md) | 3d | 5 | Done |
| 7 | [Shared replica and snapshot rebuild](./phase-07-shared-replica.md) | 3d | 4, 5, 6 | Done |
| 8 | [Billing cutover](./phase-08-billing-cutover.md) | 4d | 7 | Done |
| 9 | [Notification Hub and webhook move](./phase-09-notification-hub-and-webhooks.md) | - | - | Cancelled |
| 10 | [Email and templates](./phase-10-email-and-templates.md) | - | - | Cancelled |
| 11 | [In-app notifications](./phase-11-in-app-notifications.md) | - | - | Cancelled |
| 12 | [Tenant System migration](./phase-12-tenant-system-migration.md) | 1d | 6 | Done |
| 13 | [Suite runtime and isolation](./phase-13-suite-runtime-and-isolation.md) | 2.5d | 4-8, 12 | Done |
| 14 | [Docs and plan cleanup](./phase-14-docs-and-plan-cleanup.md) | 0.5d | 1-8, 12, 13 | Done |

## Cross-plan dependencies

| Relationship | Plan | Effect |
|---|---|---|
| Supersedes phases 11-14 in part | `261109-1625-core-billing-implementation` | Phases 12-13 (web admin, portal UI) are replaced by a Suite Console follow-up plan; phase 14 there loses the Billing-only E2E scope |
| Supersedes one decision | `261112-0816-shared-core-extraction` | Outbox and inbox implementations move from Billing to `core/core-messaging` |

## Outcome (2026-11-13)

Phases 12, 13 and 14 landed on `dev`:

- **12. Tenant System migration.** `tenant/` no longer holds an API key: `PlatformTokenProvider` exchanges its
  Platform client credentials at `POST /oauth/token`, caches the token until 60 seconds before expiry, shares one
  refresh across concurrent callers and refreshes once on a 401 (`TokenClient`). `NewCustomer`/`byExternalUser` lost
  their `tenantSystemId`, because Billing derives it from the token. `checkNoBillingDependency` now also rejects
  Platform and the service-only core modules, and an ArchUnit rule rejects any reference to another service's code.
  `./gradlew build` is green and `git grep -n "api-key\|SAMPLE_TENANT_BILLING_API_KEY" tenant` returns nothing.
- **13. Suite runtime and isolation.** Three multi-stage Dockerfiles (build context at the repo root). One PostgreSQL
  with a database, owner role and app role per service and `revoke connect` from public; one RabbitMQ user per service
  limited by regex; a Caddy that routes on Host only; a `billing-migrate` step and health-gated compose. `suite.yml`
  runs the whole stack. Both scripts pass against the running suite. Two real defects surfaced and were fixed: the
  inbox carried a foreign key to `tenants` that made a new tenant's first event impossible to record, and
  `SuiteJwtDecoders` built its Nimbus decoder without naming ES256, so it defaulted to RS256 and refused every suite
  token. The first now has `V25__inbox_without_tenant_fk.sql`; the second names ES256 for Platform and RS256 for the
  user realm, with a real-JWKS regression test. `./gradlew build` is green in all four builds.
- **14. Docs and plan cleanup.** Every listed doc rewritten for the suite model, two new docs (`platform-service.md`,
  `suite-messaging.md`), the index updated, the specification marked as the historical baseline, and the old plan's
  phases 11-14 reconciled. Every relative link resolves and both stale-claim greps are clean.

## Key decisions carried in

Central RBAC with per-app permission catalogs (`billing`, `platform`); suite tokens signed by Platform (service clients and tenant systems); state-transfer `*.changed` events for replicated data; per-service replica tables named as in Billing today (`tenants`, `tenant_domains`, `tenant_systems`) so existing foreign keys and RLS stay.
