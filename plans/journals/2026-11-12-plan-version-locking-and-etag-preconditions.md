---
title: "Plan: version locking and ETag preconditions"
date: 2026-11-12
summary: Found a Platform version race that lets the replica diverge; planned lock-first bumps plus optional If-Match
---

# Plan: version locking and ETag preconditions

## What happened
We reviewed a blog post on outbox, idempotency, concurrency control and ETag against the codebase. Outbox, inbox,
business idempotency keys and DB constraints were already in place. Concurrency control in Platform was not.

Root cause, found by reading the code and not yet reproduced in a test:
- `AccessPublisher.publishFor` reads the user without a lock, bumps `version` in memory and writes
  `set version = <in-memory value>`. Under READ COMMITTED, two concurrent access changes publish
  `user-access.changed` events with the same version and different permission sets.
- `ReplicaWriter` keeps an upsert only when `stored.version < excluded.version`, so the second event is dropped and
  the replica diverges from Platform. An unassign racing an assign can leave a revoked permission in place.
- Assign racing replace-permissions on one role is a second race. Bumping the user's version atomically does not fix
  it: the assign has to lock the role.
- Role, tenant, tenant system and entitlement writes use the same unlocked bump.

## Decision
- Lock-first row locks in Platform, in the order role (`for share` / `no key update`), then users in ascending id. The
  repository update refuses a non-increasing version.
- ETag `"<version>"`, with `If-Match` optional (412 on mismatch, missing header keeps last-write-wins). Scope is the
  Platform admin endpoints plus Billing customer and webhook endpoint. No If-None-Match/304.
- Billing gets a V24 migration adding `version` to `customers` and `webhook_endpoints`.

## Next steps
Implement `plans/261112-1335-version-locking-and-etag` with `/ak-cook`. Phases 1 and 2 can run in parallel.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
