---
title: "Enterprise suite foundation: Platform service, RabbitMQ, Notification Hub"
description: "Turn the repo into the foundation of a multi-tenant SaaS suite: extract Platform from Billing, add RabbitMQ and a Notification Hub."
status: pending
priority: P1
effort: 40d
branch: dev
tags: [refactor, backend, auth, infra, api, database]
blockedBy: []
blocks: []
created: 2026-11-12
---

# Enterprise suite foundation

## Overview

The repo stops being "Billing plus a sample tenant" and becomes the foundation of a multi-tenant SaaS suite (learning/portfolio goal). Platform (tenant, domain, user, centralized RBAC, app entitlement, machine credentials and suite tokens) is extracted from `billing/contexts/platform` into its own deployable `platform/` build. Events cross systems through RabbitMQ. A new `notification/` build (Notification Hub) takes over outbound webhooks from Billing and adds email with templates and in-app realtime notifications. Billing becomes one app of the suite and keeps local replicas of the Platform data it needs.

Source: `/ak-advise` session of 2026-11-12. Preview: [suite preview](../reports/advise-261112-1400-enterprise-suite-pivot-preview.html).

## Done means

- `docker compose up` runs Keycloak, RabbitMQ, Platform, Billing and Notification Hub, each service with its own database.
- Creating a tenant in Platform makes its replica appear in Billing and the Hub (integration tests per service and the suite smoke script).
- Platform exposes a snapshot API; the shared replica consumer in `core/` rebuilds a replica from zero.
- Billing has no tenant, user or role business logic: `billing/contexts/platform` is deleted, the identity tables and the bearer-secret path are gone, cross-cutting code lives in `core/`.
- `tenant/` calls Billing with a Platform-issued JWT and verifies webhooks from the Hub.
- `invoice.created` produces one email (Mailpit) and one in-app notification.
- ArchUnit, Gradle and database-level checks block any service from reaching another service's database.
- Docs describe the suite model.

## Out of scope

Business apps (CRM, HR...), charging tenants for suite use, SMS or push, payment providers other than Stripe, custom domains, an API gateway with logic, CDC or event sourcing, migrating existing data, the Suite Console UI (separate follow-up plan, see phase 14).

## Constraints

- Keycloak authenticates only; Platform authorizes; apps only enforce.
- No shared database. Shared code lives only in `core/`; contracts live in `contracts/`.
- Keep the conventions: `api`/`domain`/`application`/`adapter` source sets, jOOQ with committed generated code, ArchUnit with arch-fixtures, `UnitOfWork`.
- RabbitMQ: one topic exchange per producer (`platform.events`, `billing.events`), one queue per consuming app with a dead-letter exchange.
- Replicated events carry a monotonic `version`; consumers drop older versions.
- Email and in-app go through a `notification.requested` command sent by the owning app; webhooks are the Hub forwarding public events.
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
| 8 | [Billing cutover](./phase-08-billing-cutover.md) | 4d | 7 | Pending |
| 9 | [Notification Hub and webhook move](./phase-09-notification-hub-and-webhooks.md) | 4d | 7, 8 | Pending |
| 10 | [Email and templates](./phase-10-email-and-templates.md) | 3d | 9 | Pending |
| 11 | [In-app notifications](./phase-11-in-app-notifications.md) | 2d | 10 | Pending |
| 12 | [Tenant System migration](./phase-12-tenant-system-migration.md) | 2d | 6, 9 | Pending |
| 13 | [Suite runtime and isolation](./phase-13-suite-runtime-and-isolation.md) | 3d | 4-12 | Pending |
| 14 | [Docs and plan cleanup](./phase-14-docs-and-plan-cleanup.md) | 1d | 1-13 | Pending |

## Cross-plan dependencies

| Relationship | Plan | Effect |
|---|---|---|
| Supersedes phases 11-14 in part | `261109-1625-core-billing-implementation` | Phases 12-13 (web admin, portal UI) are replaced by a Suite Console follow-up plan; phase 14 there loses the Billing-only E2E scope |
| Supersedes one decision | `261112-0816-shared-core-extraction` | Outbox and inbox implementations move from Billing to `core/core-messaging` |

## Key decisions carried in

Central RBAC with per-app permission catalogs; suite tokens signed by Platform (service clients and tenant systems); state-transfer `*.changed` events for replicated data; per-service replica tables named as in Billing today (`tenants`, `tenant_domains`, `tenant_systems`) so existing foreign keys and RLS stay.
