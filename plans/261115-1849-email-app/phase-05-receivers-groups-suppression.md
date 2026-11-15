---
phase: 5
title: "Receivers, groups, suppression, unsubscribe"
status: done
priority: P1
effort: "M"
dependencies: [3]
---

# Phase 5: Receivers, Groups, Suppression, Unsubscribe

## Goal

A Tenant manages its customers as receivers, organises them into static groups, and the system can answer — for any
address and any kind of email — whether sending is allowed.

## Data model (context `audience`)

- `receivers(id, tenant_id, email, email_normalized, first_name?, last_name?, attributes jsonb, status, version, created_at, updated_at, unique (tenant_id, email_normalized))`;
  GIN index on `attributes` (used in phase 9). `email_normalized` is lower-cased and trimmed.
- `receiver_groups(id, tenant_id, name, version, unique (tenant_id, name))`
- `group_members(tenant_id, group_id, receiver_id, added_at, primary key (group_id, receiver_id))`
- `suppressions(tenant_id, email_normalized, scope, reason, created_at, primary key (tenant_id, email_normalized))`,
  `scope` = `MARKETING` | `ALL`, `reason` = `UNSUBSCRIBED` | `MANUAL`
- RLS on every table. A receiver is personal data: no address in logs, metrics or exception messages.

## Rules

- **Unsubscribe** writes a `MARKETING` suppression and flips the receiver to `UNSUBSCRIBED`; it is idempotent.
- **Manual block** writes `ALL`; it stops transactional mail too.
- A **transactional** email ignores a `MARKETING` suppression and honours `ALL`; a **marketing** email honours both.
- A re-subscribe is an explicit API action by the Tenant (it asserts the customer consented); the endpoint records who did it.
- Attributes: keys `[a-z][a-z0-9_]{0,31}`, scalar values or small arrays, ≤ 20 keys, total ≤ 4 KiB.
- The `api` port `SendEligibility.check(tenant, address, kind)` is the only way `sending` asks about suppression.

## Files to create

- `mailroom/contexts/audience/src/{domain,application,adapter,api}/...` for receiver, group, membership, suppression
- `mailroom/apps/app-boot/src/main/resources/db/migration/V3__audience.sql`; `contracts/openapi/mailroom.yaml` (receivers, groups, suppressions)
- Tests under `mailroom/apps/app-boot/src/test/java/dev/ngb/mailroom/audience/`

## Endpoints

`PUT /receivers/{id}`-style upsert by address (`PUT /receivers` with `email`), `GET /receivers` (filter, cursor paging),
`GET|PATCH|DELETE /receivers/{id}`, `POST /receivers/bulk` (≤ 1,000 per call, per-item result, idempotent),
`POST /groups`, `GET /groups`, `PUT /groups/{id}`, `DELETE /groups/{id}`, `POST|DELETE /groups/{id}/members`
(≤ 1,000 ids per call), `GET /groups/{id}/members`, `GET|POST|DELETE /suppressions`.
`DELETE /receivers/{id}` is a hard delete that also removes group memberships (a customer's erasure request) and keeps
only the suppression row so a deleted-then-re-imported address is still not mailed. Permissions `mailroom:receiver:read|manage`.

## Verification

- Unit: normalisation, attribute limits, the eligibility truth table (kind × scope × receiver status).
- Integration: bulk upsert is idempotent and reports per-item errors; a receiver in two groups appears once per group;
  deleting a group does not delete receivers; erasure removes the receiver and memberships but keeps the suppression.
- RLS test for every table; API test that tenant B cannot read or mutate tenant A's receiver by id.
- A log-capture test asserts no address appears in logs for create, bulk and delete.

## Risks

- Import volume: bulk endpoints are capped and paged; a 1M-row import is a client loop, not a single request.
- Hard delete versus audit: the suppression row deliberately outlives the receiver; document it in `docs/mailroom.md`.
