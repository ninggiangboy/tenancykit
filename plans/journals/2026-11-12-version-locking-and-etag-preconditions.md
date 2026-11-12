---
title: Version locking and ETag preconditions
date: 2026-11-12
summary: "Unit-of-work locking for every Platform version bump, plus ETag and optional If-Match on Platform and Billing updates"
---

# Version locking and ETag preconditions

## What happened

Implemented plans/261112-1335-version-locking-and-etag in five phases, one commit each.

Platform had a version race: every write read the row, bumped `version` in memory and wrote it back
without a lock. Under READ COMMITTED two concurrent changes on one user wrote the same version, and the
replica keeps only a strictly newer one, so it silently dropped the second state. An unassign racing an
assign could leave a revoked permission in place.

## What changed

- Every Platform version bump now holds the row lock across read, bump and write: user `for update`, role
  `for no key update` (rename/replace) or `for share` (assign/unassign), tenant and tenant system
  `for no key update`, entitlement `for update` with `insert ... on conflict do nothing` for the first
  row. Each repository `update` carries `and version < :new` and throws on zero rows.
- The role fan-out locks holders in ascending id order, so an assign and a replace-permissions on one role
  serialise and no wait cycle forms.
- `ExpectedVersion` parses If-Match with RFC 9110 strong comparison; `PreconditionFailedException`
  becomes a 412 with the current ETag in both apps. Platform writes check it under the lock and return
  the new ETag; Billing customers and webhook endpoints gained V24 version columns, lock-by-id and the
  same conditional update. Visibility is checked before the lock, so an unmapped customer is 404, not 412.
- `docs/concurrency-and-locking.md` records the lock order and the conditional-update rule.

## Verification

- `./gradlew build` passes in `core/`, `platform/` and `billing/`.
- New races cover user-vs-user, unassign-vs-assign, assign-vs-replace-permissions and first
  enable-vs-disable; each asserts distinct versions and a replica that agrees in both delivery orders.
- Billing's deadlock stress test still passes.
- `contracts/openapi/billing.yaml` was regenerated: the diff adds only If-Match, the ETag header and 412.

## Notes

The Billing work to remove the Notification Hub and cut over to the replica was already dirty in the tree
when this started; none of it is part of these commits.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
