---
title: planned-the-notification-service
date: 2026-11-15
summary: Eight-phase plan for a fourth deployable that turns Platform state transitions into typed in-app notifications and already-rendered email.send commands, with mailer untouched.
---

# planned-the-notification-service

## What happened

Wrote `plans/261115-0619-notification-service/`: `plan.md` plus eight phase files (64 checkboxes) for a new
`dev.ngb.notification` service. It consumes `platform.events` on its own queue, keeps the standard Platform replica
(tenants, domains, tenant systems, user grants, entitlements), turns three state-transfer event types into five fixed
notification types for **tenant users**, renders tenant-overridable templates, stores in-app notifications and
publishes already-rendered `email.send` commands to `mailer.commands` through an outbox and broker relay.

Scope came from an `/ak-advise` interview that locked two boundaries: recipients are Platform users only (customer and
payment notifications belong to the Tenant System, so no Billing data is read), and the event→template mapping is a
fixed catalog in code rather than a tenant-configurable rules engine. Channels are email plus a stored in-app inbox;
real-time SSE was cut.

## Decision

Four adversarial passes ran against the first draft and every finding was checked against source. Six were blocking:

1. **The outbox copy was the wrong vintage.** `BrokerRelay.claim` predicates on `publish_status` and
   `publish_next_at`, which Billing added in `V22__outbox_broker_cursor.sql`; the `V2` table has neither, so the first
   relay tick would fail with `column publish_next_at does not exist`.
2. **`event_inbox` must have no foreign key to `tenants`.** Billing shipped that FK and removed it in
   `V25__inbox_without_tenant_fk.sql`: the first `tenant.changed` for a brand-new tenant could not be recorded, so that
   tenant never reached the replica at all.
3. **`MessagingProperties` exposes `exports`, not `exchanges`**, and an empty route set makes `relayDue()` a silent
   no-op — so the plan now names the property correctly and asserts it is non-empty at startup.
4. **Nothing in the draft could read the pre-upsert row.** `core-replica`'s `ReplicaEventHandler.handle` is a one-liner
   with no hook, so Phase 3 owns an `InboundEventHandler` that reads the previous snapshot, upserts, then dispatches.
5. **jOOQ read classes had been placed in `application`**, which has neither jOOQ on its classpath nor permission to
   use it. Ports are implemented as `*Service` classes and queries live in `adapter/.../persistence`.
6. **`@Authenticated` admits a Tenant System credential**, so a machine could have written a preference row. A
   `TenantScopeAndUserGuard` now refuses platform scope and non-principal callers, and both are tested.

Also applied: worker-only `ReplicaBootstrap`, a health contributor named `replicaState` included in both profiles, the
eleven-component `Profile` copy, `@NamedInterface("api")` on the platform context, exclusion of the platform
pseudo-tenant from the tenant sweep, `fromName` omitted when sanitisation empties it, a housekeeping job for four
retention boundaries, `unreadCount` in the list response, and id-only cursor paging.

Rejected: collapsing to one context (the replica tables must not be owned by the context that reads them) and dropping
the template/dispatch APIs (both are stated goals).

**Coalescing was cut after review.** A role edit emits one event per affected user, so a burst is one email per
*different* user; suppressing repeats for one user needed a lock or a conditional insert, and a racy window that drops
mail is worse than the rare duplicate.

## Next steps

Start with `/ak-cook plans/261115-0619-notification-service/plan.md`, one commit per phase. Phases 1–3 are setup
(docs, build, replica); Phase 4 is independently testable; Phase 5 is the first phase that produces a notification.
Phase 8 is the only one that needs the whole compose stack, Terraform and CI.

Open boundary kept deliberately: a Billing-origin notification would need recipient data Billing does not publish, so
it is a new contract or producer-side resolution — not something this plan pre-solves. A Keycloak audience mapper on a
running realm is a manual admin-API step, because the realm export is import-only.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
