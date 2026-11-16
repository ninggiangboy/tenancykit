---
phase: 2
title: "Identity seams, Platform purpose and refund grant"
status: done
priority: P1
effort: "L"
dependencies: [1]
---

# Phase 2: Identity Seams, Platform Purpose and Refund Grant

## Goal

Everything outside `workflow/` that the app needs exists: its databases and roles, broker user, Platform service client and
entitlement, the `purpose` of a Tenant System with its grant rule, and the one-line Billing permission change, each verified
on a running stack.

## Context

Touchpoints for a new app come from `git grep -l -i mailroom -- . ':!mailroom' ':!plans' ':!docs' ':!attribution' ':!survey'`
(re-run it now; phase 2 of the crm and loyalty plan lists the usual files: `.env.example`, `docker-compose.yml`,
`ops/postgres/init/00-databases.sh`, `ops/rabbitmq/declare-users.sh`, `ops/keycloak/realm-export.json`, Platform
`application.yml`). Compose app services, Caddy, CI and Terraform belong to phase 15. Survey, attribution and booking are being
edited elsewhere: every edit is append-only.

Three user decisions change code outside the new app:

1. **`purpose` on a Tenant System** (`GENERIC` default, `WORKFLOW`). Rule: a credential of a `WORKFLOW` system may carry app
   codes and `workflow:*` codes, never `platform:*`. A `GENERIC` system is unchanged and may also carry `workflow:*` codes,
   because a Tenant's own system manages definitions through the same API. Changing `purpose` after creation is refused.
2. **Auto-provision.** When the `workflow` entitlement is enabled for a Tenant, Platform creates one Tenant System named
   `workflow` with purpose `WORKFLOW` and no credential; enabling again reuses it, disabling the entitlement disables the
   system (the existing kill switch every app already honours). The entitlement lives in the `tenancy` context
   (`SetAppEntitlementUseCase`) and Tenant Systems in `integration`; find how Platform contexts already react to each other
   (domain events and listeners) before choosing the seam, and keep the contexts' dependency direction legal under ArchUnit.
   The credential is not created: its secret is shown once and its scopes are the Tenant's choice.
3. **`billing:refund:create` becomes grantable** to a Tenant System. Before flipping it, verify Billing's refund endpoint
   serves a Tenant System principal with no Java change; the generated `contracts/openapi/billing-tenant-system.yaml` is
   rebuilt from the catalog.

## Files to modify

- `platform/apps/app-boot/src/main/resources/application.yml` (a `workflow` service client, replica scope only)
- Platform tenancy context (entitlement reaction) and integration context: `TenantSystem` domain, create command, read model, controller, persistence, migration,
  replica event field, OpenAPI; tests next to each
- `contracts/permissions/billing.yaml` (one flag), `contracts/openapi/platform.yaml`, `contracts/openapi/billing-tenant-system.yaml`
- `.env.example`, `docker-compose.yml` (Platform env and the `workflow` database variables only),
  `ops/postgres/init/00-databases.sh`, `ops/rabbitmq/declare-users.sh`, `ops/keycloak/realm-export.json`

## Tasks

1. Bring up `postgres rabbitmq keycloak platform` and record that existing service clients can authenticate (copy the check
   crm and loyalty used for mailroom); report any surprise instead of fixing other apps.
2. Add the `workflow` service client (`platform:replica:read`, own secret hash) and the `workflow` entitlement value wherever
   entitlement codes are enumerated (search for where `crm` and `loyalty` appear).
3. Create `workflow` and `workflow_owner/workflow_app` roles, the broker user (consume `platform.events` and its own queues,
   publish nothing to another app's exchange), and the Keycloak audience mapper, mirroring crm's entries.
4. Platform: add `purpose` with a migration (default `GENERIC`, backfill all rows), the grant rule in
   `ManageCredentialUseCase.requireGrantable`, the field on create and read, and on the replicated tenant-system event so
   apps can see it. Update `contracts/openapi/platform.yaml`. Tests: a `WORKFLOW` system refused a `platform:*` code, accepted
   `billing:*` and `workflow:*`; purpose immutable; existing systems unchanged.
4b. Auto-provision per context item 2: a use case reacting to the entitlement change, idempotent under concurrent enables.
    Tests: enable creates exactly one system with purpose `WORKFLOW` and no credential; enable twice creates one; disable
    disables it and a token request for its credential is refused; re-enable restores it; other apps' entitlements create
    nothing.
5. Billing: flip `billing:refund:create`, regenerate the Tenant System OpenAPI, and prove with a test or a scripted call that a
   Tenant System token with the code creates a refund. If Billing needs a Java change, stop and ask.
6. Record in this phase's notes the exact list of out-of-tree files touched, so phase 15 can reconcile.

## Validation

- `POST /oauth/token` as client `workflow` succeeds with scope `platform:replica:read` only.
- Platform tests green including the new purpose rule; migration applies to a database with existing Tenant Systems.
- A Tenant System credential with `billing:refund:create` obtains a token and creates a refund in Billing on the compose stack.
- `git diff --stat` outside the new app lists only the files above.

## Risks

- The refund grant widens refund power. Mitigation in the plan's risk register; do not add an amount cap here (out of scope).
- A migration on Platform's schema: back up the database first and apply it to a copy, per project rules.

## Notes — out-of-tree files touched

Platform:

- `platform/apps/app-boot/src/main/resources/application.yml` (the `workflow` service client)
- `platform/apps/app-boot/src/main/resources/db/migration/V6__integration_tenant_system_purpose.sql`
- `platform/apps/app-boot/src/main/java/dev/ngb/platform/boot/replica/SnapshotController.java`
- `platform/contexts/integration/src/{domain,application,api,adapter}/.../tenantsystem/` (the `purpose` field, its enum, the
  create command, the view, the controller, both repositories)
- `platform/contexts/integration/src/application/.../credential/command/ManageCredentialUseCase.java` (the grant rule)
- `platform/libs/jooq-schema` regenerated by `JooqSchemaTest -Djooq.update=true`

Contracts:

- `contracts/events/platform/tenant-system.changed.schema.json` (`purpose`, required)
- `contracts/permissions/billing.yaml` (`billing:refund:create` grantable)
- `contracts/openapi/platform.yaml`, `contracts/openapi/billing.yaml`,
  `contracts/openapi/billing-tenant-system.yaml` regenerated by their contract tests

Billing:

- `billing/contexts/payment/.../refund/...` (the caller is passed through and the payment's customer is checked with
  `CustomerVisibility`; a Tenant System that did not map the customer gets 404)
- `billing/apps/app-boot/src/main/java/.../PaymentConfiguration.java` and
  `billing/libs/shared-kernel/.../BillingPermissions.java` (wiring and the javadoc)

Ops and env:

- `.env.example`, `docker-compose.yml`, `ops/postgres/init/00-databases.sh`, `ops/rabbitmq/declare-users.sh`,
  `ops/keycloak/realm-export.json` (append-only; the compose services themselves are phase 15)

Decision taken while executing (asked and confirmed): the refund grant ships with caller scoping, so a Tenant System
refunds only the customers it mapped, exactly as its refund reads already do. Without it the grant would widen refunds
across Tenant Systems inside one Tenant.

