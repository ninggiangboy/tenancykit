---
phase: 14
title: "Docs and plan cleanup"
status: pending
priority: P2
effort: "1d"
dependencies: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13]
---

# Phase 14: Docs and plan cleanup

## Goal

The docs describe the suite (Platform, Billing, Notification Hub, RabbitMQ, replicas), no doc still says Billing owns tenants, users or authorization, and the old Billing plan points at the follow-up Suite Console plan.

## Files

Modify (read each before editing; keep each under the 800-line limit):
- `README.md`: the directory table gains `platform/` and `notification/`; run instructions point at compose and the smoke script.
- `docs/README.md`: index entries for the new docs.
- `docs/overview-and-scope.md`: "Two systems" becomes the suite model; responsibilities and division rules per service; non-goals updated (Suite Console is a follow-up).
- `docs/authentication-and-authorization.md`: Platform authorizes; permission catalogs; suite tokens for tenant systems and service clients; replica-based enforcement and its revocation latency; the rejected options (permissions in Keycloak tokens, introspection per request).
- `docs/architecture.md`: system diagram, decision ledger rows (Platform service, RabbitMQ over Kafka with the snapshot API, state-transfer events, `core-messaging` and `core-replica`), module boundaries per service.
- `docs/webhooks-and-events.md`: delivery moved to the Hub; Standard Webhooks headers; AMQP between services.
- `docs/tenant-system.md`: token flow and Hub endpoint registration.
- `docs/multi-tenancy-and-data-isolation.md`: per-service databases and roles; replicas keep FKs and RLS.
- `docs/security-and-reliability.md`: broker users, DLQ runbook link, token signing key rotation.
- `docs/runtime-profiles.md`: which profile runs relays and listeners.
- `contracts/README.md`: final layout.

Create:
- `docs/platform-service.md`: tenancy, entitlement, access, integration modules; snapshot API; events.
- `docs/notification-hub.md`: channels, `notification.requested`, templates, delivery engine, SSE and its single-instance limit.
- `docs/suite-messaging.md`: topology, envelope, versioned replicas, rebuild procedure, DLQ handling.

Modify plans:
- `plans/261109-1625-core-billing-implementation/plan.md`: phases 12 and 13 set to `Cancelled (superseded by the Suite Console follow-up plan)`; phase 11 status checked against the code (`tenant/` exists) and corrected; phase 14 scope note.
- This plan's `plan.md`: status `completed` with outcome notes.

## Implementation steps

1. Read every listed doc; list each stale statement with its file and line.
2. Rewrite the smallest owning section for each; create the three new docs.
3. Check links: every relative link in `docs/` and `contracts/` resolves (`grep -o '](\.[^)]*)' -r docs contracts` then test each path).
4. Grep for stale claims: `git grep -nE "Billing (owns|manages) (Platform|Tenants|Users|Roles)|Billing authorizes|two systems|Billing-Signature" docs contracts README.md` returns nothing.
5. Update the old plan; write the journal entry with `/ak-journal`.

## Todo

- [ ] Every listed doc updated; three new docs written
- [ ] Links verified
- [ ] Stale-claim grep clean
- [ ] Old plan phases 11-14 reconciled
- [ ] Journal entry

## Verification

- The stale-claim grep above returns nothing.
- `wc -l docs/*.md` shows no file above 800 lines.

## Next steps (after this plan)

- Suite Console plan: one web app with app switcher (from `/api/v1/me` enabled apps), Platform admin screens, Billing screens, notification bell over the SSE stream; replaces old phases 12-13.
- First business app (for example CRM) to prove the extension trace: a new build, `contracts/permissions/<app>.yaml`, an `AppCode`, a proxy route, its own replica migration; Platform, Billing and the Hub unchanged.
