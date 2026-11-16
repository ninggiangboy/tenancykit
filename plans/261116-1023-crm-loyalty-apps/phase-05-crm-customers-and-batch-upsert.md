---
phase: 5
title: "crm customers, batch upsert and lookup"
status: done
priority: P1
effort: "L"
dependencies: [3]
---

# Phase 5: crm Customers, Batch Upsert and Lookup

## Goal

A Tenant's system creates, updates, reads and lists customers, one at a time or in batches, keyed by its own `external_ref`,
and every new customer is announced to the Tenant's subscribed webhook endpoints as `customer.created`.

## Context

The Tenant's system owns identity; crm never matches by phone or email. Updates follow the suite's ETag and `If-Match` rules
(`docs/concurrency-and-locking.md`, "Conditional updates"; `core/core-kernel/.../version/{ConditionalUpdate,ExpectedVersion}`).
Natural-key idempotency uses `core/core-kernel/.../idempotency/{RepeatableCreate,Fingerprint}`. Migration range `V10`.

## Files to create

- `crm/apps/app-boot/src/main/resources/db/migration/V10__customers.sql`; regenerate and commit `crm/libs/jooq-schema`
- `crm/contexts/customers/src/{domain,application,api,adapter}/...` for the `customer` feature (command, query, port,
  service, jOOQ persistence, REST controller, batch controller)
- Tests: `CustomerApiIntegrationTest`, `CustomerBatchIntegrationTest`, `CustomerIsolationTest`

## Tasks

1. **Table** `customers(tenant_id, id uuid, external_ref, display_name, phone, phone_digits, email, status, version,
   created_at, updated_at)` with RLS, unique `(tenant_id, external_ref)`, indexes `(tenant_id, phone_digits)` and
   `(tenant_id, lower(email))`, and a keyset index `(tenant_id, created_at desc, id desc)`. `status` is `ACTIVE` or `ERASED`.
2. **Rules.** `external_ref` is 1-128 characters, trimmed, immutable. `display_name` 1-200 characters. `phone` is stored as
   sent (at most 32 characters), `phone_digits` is derived by stripping everything but digits. `email` is lower-cased,
   at most 254 characters, loosely validated. IDs are UUID v7 from the shared `IdGenerator`.
3. **Operations.** `PUT /customers/{externalRef}` replaces the representation and answers `201` (created) or `200` (updated or
   unchanged), with an `ETag` of the version; an identical body changes nothing, bumps no version and publishes nothing.
   `PATCH` takes `If-Match` (`412` on mismatch) and changes only the given fields. `GET /customers/{externalRef}` and a plain
   `GET /customers` (keyset pagination, no filters yet; filters arrive in phase 8).
4. **Event.** On creation, append `customer.created` to the outbox in the same transaction; the webhook context of phase 3
   turns it into deliveries.
5. **Batch.** `POST /customers/batch`: up to the cap of items, each its own transaction, ordered results
   `{externalRef, outcome: CREATED|UPDATED|UNCHANGED|FAILED, code?}`. A malformed item fails alone; a body over the cap or a
   malformed envelope fails with `400`. Two items with the same `externalRef` in one batch apply in order.
6. **Permissions.** `crm:customer:read` for reads, `crm:customer:manage` for writes, enforced by the contract test.
7. **Search index decision.** If trigram search is wanted in phase 8, verify here that the owner role may create `pg_trgm`
   (`create extension if not exists pg_trgm` in a migration); if not, plan prefix search on `lower(display_name)` with
   `text_pattern_ops` and record the choice in `docs/crm.md`.

## Verification

- `cd crm && ./gradlew build` green.
- Tests prove: two tenants may use the same `external_ref`; a tenant-B token gets `404` for tenant A's customer; a retried
  `PUT` with the same body is `UNCHANGED` and emits one event in total; concurrent `PUT`s of a new ref create exactly one row;
  a stale `If-Match` gives `412`; a batch with one bad item still writes the others and reports each.
- `OpenApiContractTest` passes with the new operations.

## Risks

- Concurrent first-time upserts of one ref race on the unique key: use the insert-on-conflict path, then re-read, and test it
  with 50 parallel callers.
