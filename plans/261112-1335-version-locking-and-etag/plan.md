---
title: "Version locking and ETag preconditions"
description: "Make every Platform version bump safe under concurrency, then expose aggregate versions as ETags with optional If-Match on Platform and Billing update endpoints."
status: completed
priority: P1
effort: "3d"
tags: [concurrency, platform, billing, http]
created: 2026-11-12
---

# Version locking and ETag preconditions

## Outcome

1. **No two Platform events for one row carry the same `version` with different state.** Every Platform write that
   bumps a `version` holds the row lock while it reads, bumps and writes. The replica (`ReplicaWriter`, which keeps an
   upsert only when `stored.version < excluded.version`) then always ends up equal to Platform.
2. **Clients can make updates conditional.** Single-resource responses carry `ETag: "<version>"`. An update that sends
   `If-Match` with a stale tag gets `412 Precondition Failed`. An update without `If-Match` behaves as it does today.

## Why (evidence)

- `AccessPublisher.publishFor` reads the user without a lock, bumps `version` in memory and writes
  `set version = <in-memory value> where id = ?`. Under READ COMMITTED (the default; nothing overrides it), two
  concurrent access changes on one user both write the same version, and each publishes the permission set from its own
  snapshot. The replica then drops the second event as a duplicate. With an unassign racing an assign, the replica can
  keep a permission that was revoked.
- Assign versus replace-permissions on the same role is a second race. The assign computes the user's permissions with
  the role's old permissions, and the replace never sees the new assignment. Bumping the user's version atomically does
  not fix this. The assign must lock the role.
- Role, tenant, tenant system and entitlement writes use the same in-memory bump with no lock.
- Billing `customers` and `webhook_endpoints` have no version column. `PUT /customers/{id}` and
  `PATCH /webhook-endpoints/{id}` are last-write-wins.

## Decisions (confirmed with the user, 2026-11-12)

- **`If-Match` is optional.** When present it is enforced (412 on mismatch). When absent, today's behaviour stays. No
  428 Precondition Required.
- **Endpoint scope.**
  - Platform: tenant rename and deactivate; role rename and replace-permissions; user patch and direct permission
    grant; tenant-system update; entitlement set.
  - Billing: customer update and deactivate; webhook endpoint PATCH.
  - Out of scope: Stripe configuration, the webhook secret, portal return URLs and the sample tenant app.
- **No `If-None-Match` / 304.** GET responses still return an ETag, so 304 can be added later without changing the
  contract.
- **Locking style is lock-first (pessimistic row locks)**, not `where version = ?` retry loops. This matches Billing's
  existing practice and `docs/concurrency-and-locking.md`. The HTTP precondition is then a plain compare under the lock.

## Non-goals

- `If-None-Match` and 304.
- `If-Match` on create, on role assign/unassign, or on credential endpoints.
- Changing event schemas: `version` already exists in every `*.changed` payload.
- The sample tenant app (`tenant/`).

## Phases

| # | Phase | Depends on | Status |
|---|-------|-----------|--------|
| 1 | [Platform lock-first version bumps](./phase-01-platform-lock-first-versions.md) | none | Pending |
| 2 | [Shared ETag and precondition primitives](./phase-02-etag-precondition-primitives.md) | none | Pending |
| 3 | [Platform ETag and If-Match](./phase-03-platform-etag-if-match.md) | 1, 2 | Pending |
| 4 | [Billing version columns, ETag and If-Match](./phase-04-billing-etag-if-match.md) | 2 | Pending |
| 5 | [Docs](./phase-05-docs.md) | 1, 3, 4 | Pending |

Phases 1 and 2 can run in parallel because their files do not overlap. Phase 4 can run in parallel with phase 3.

## ETag contract (shared by phases 2 to 4)

- `ETag: "<version>"` is a strong tag holding the aggregate's `version` as a decimal. It is sent on single-resource
  GET responses and on every in-scope update response.
- `If-Match` follows RFC 9110 strong comparison:
  - `*` passes.
  - A comma-separated list passes when any entry matches.
  - A weak tag (`W/"3"`) never matches.
  - A malformed header gets 400.
- A mismatch, or `If-Match` on an entitlement row that does not exist yet, gets `412` with a ProblemDetail. The current
  `ETag` is sent in the 412 response headers.
- 409 stays for business conflicts, such as changing a deactivated customer.
- A version may move without any visible field changing. For example, a user's version bumps when one of their roles
  changes permissions. A spurious 412 is acceptable; a missed one is not.

## Success criteria

- [ ] A concurrency test for each race (user × user, assign × replace-permissions, entitlement × entitlement) shows that
      Platform and the replica agree and that every published event for one row has a distinct version.
- [ ] Every in-scope endpoint returns an `ETag`, rejects a stale `If-Match` with 412, and accepts a missing `If-Match`.
- [ ] `./gradlew build` passes in `core/`, `platform/` and `billing/`. Docker-backed tests are reported if they cannot
      run.
- [ ] `contracts/openapi/billing.yaml` and `billing-tenant-system.yaml` are regenerated, and the diff only adds
      `If-Match`, the `ETag` header and 412 responses.
- [ ] `docs/concurrency-and-locking.md` lists the Platform lock order and the precondition rule.

## Unresolved questions

None. The three scope decisions above were answered on 2026-11-12.

<!-- slug: version-locking-and-etag -->
