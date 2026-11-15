---
title: Made notification templates suite-wide and let Platform users be notified
date: 2026-11-15
summary: "Templates lost tenant_id and RLS and became platform-only content; Platform administrators now receive the three ACCESS_* notifications, which needed the mailer refusal removed, a mailer_tenants row and a fixed Caddy route order."
---

# Made notification templates suite-wide and let Platform users be notified

## What happened

Acted on an `/ak-advise` review of the notification service. Two independent changes landed, both inside the
uncommitted `notification/` tree plus a small mailer and ops tail.

**A. Templates became the suite's content.** `V2__templates.sql` was edited in place (nothing had ever run it): the
`tenant_id` column and the `enable_tenant_rls` call are gone and the key is `(template_key, locale)`. The two template
ports lost their `TenantId` parameter, `JooqTemplateSource`/`JooqTemplateOverrides` stopped filtering by tenant,
`SaveTemplateOverrideUseCase` no longer needs a `TenantContext`, and `notification:template:read|manage` moved to
`scopes: [platform]`. jOOQ was regenerated; the committed schema had no `tenant_id` for `notification_templates`.

**B. Platform administrators became recipients.** The `tenantScoped()` filter was removed from `AccessGrantedRule`,
`AccessChangedRule` and `AccessDisabledRule`; the two tenant-wide rules keep resolving their audience from tenant users
only. `TenantScopeAndUserGuard` was rewritten from one blanket "tenant scope on every path" rule into three per-group
rules. Mailer stopped refusing the Platform tenant, and a new `V6__platform_tenant.sql` registers that tenant in
`mailer_tenants`.

## Decision

Three things the advice did not cover, all found by reading the call paths:

1. **The job sweep would have stranded the email.** `TenantSweep` iterated `TenantDirectory.activeTenantIds()`, which
   deliberately excluded the platform pseudo-tenant. A Platform user's `email.send` would have been written to the
   outbox and never relayed, and their retained rows never purged. Added `sweptTenantIds()` (platform id first, then
   the active tenants) and pointed the sweep at it. The old exclusion existed precisely because mailer refused the
   platform tenant, so removing that refusal is what makes including it correct.
2. **Caddy's route order made the Platform inbox unreachable.** `@platformHost` matched before `@notificationPath`, so
   `platform.localhost/notification/...` would have been answered by Platform. Moved the notification rule above the
   platform-host rule. A smoke test would have caught it, but only after the whole stack was up.
3. **No `SHARED` credential row is needed.** The advice asked for one; an absent `mailer_credentials` row already means
   "send through the suite's own identity", which is exactly the `SHARED` behaviour Platform mail should have. Only the
   `mailer_tenants` row is load-bearing, so only that was added. That is less ops data, not more.

The guard's `tenantOnly` group (settings and the dispatch ledger) does not require a user principal, because it never
did; only the self-service group does. An earlier draft made it stricter and would have narrowed existing behaviour
without being asked.

## Evidence

- `notification`: 44 tests pass, including 11 new ones — `TenantScopeAndUserGuardTest` (6 cases: tenant scope refused
  on templates, platform scope allowed on templates, platform refused on settings and ledger, tenant allowed, inbox
  allowed at both scopes, machine refused) and `TemplateOverrideTest` (5 cases: two tenants render the same override,
  a missing override is empty, delete removes it for everyone, a second save bumps the revision, and the table has
  neither `tenant_id` nor RLS).
- `JooqSchemaTest` and `OpenApiContractTest` pass after regeneration; `x-allowed-callers` is now per group
  (`platform-user` for templates, both for the inbox, `tenant-user` for settings and the ledger).
- `mailer`: the refusal test became `aCommandAddressingThePlatformTenantIsAccepted` and passes; the whole build is green.
- `platform`, `billing` and `tenant` builds are green, so the platform-scoped template codes did not break Platform's
  catalog tests.
- `grep -ri "tenant-overridable|tenant override" docs contracts notification` returns nothing.

Not run: `docker compose up`, `ops/smoke/notification-smoke.sh` and `ops/smoke/isolation.sh` (no stack was brought up),
and no Terraform validation.

## Next steps

Bring the stack up and run both smoke scripts; the new Platform-user case in `notification-smoke.sh` and the new
platform-tenant RLS check in `isolation.sh` are what prove the delivery path end to end. Then commit in two parts as the
advice asked: templates first, Platform recipients plus mailer second.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
