---
phase: 9
title: "crm erasure and the webhook contract proof"
status: pending
priority: P1
effort: "M"
dependencies: [8]
---

# Phase 9: crm Erasure and the Webhook Contract Proof

## Goal

A Tenant's system can erase a customer's personal data in crm in one idempotent call, `customer.erased` reaches the Tenant's
webhook endpoints, and tests prove that every event crm sends matches its schema and that crm needs no other app, and no
other app needs crm.

## Context

Orders keep their lines (a business record that holds no person once the customer is anonymized); everything that can hold a
person's data is cleared. Migration range `V50`. Erasure in crm does not reach loyalty: a Tenant that uses both calls
loyalty's erasure endpoint too (phase 16), or forwards the `customer.erased` webhook itself. This phase closes the crm side.

## Files to create

- `crm/apps/app-boot/src/main/resources/db/migration/V50__erasure.sql`; regenerate the jOOQ schema
- `crm/contexts/customers/...` feature `erasure`; a `CustomerEventPublisher` addition for `customer.erased`
- Tests: `CustomerErasureIntegrationTest`, `PersonalDataColumnsTest`, `EventContractTest`, `WebhookDeliveryContractTest`

## Tasks

1. **Table** `erasure_requests(tenant_id, ref_hash, customer_id, erased_at)` so a retry of the same call is a replay. `ref_hash`
   is `sha256(tenant_id ‖ external_ref)`, never the ref itself: the POS's own id is what ties a record to a person, and the
   point of erasure is to cut that tie.
2. **Operation.** `POST /customers/{externalRef}/erasure` (`crm:customer:erase`). In one transaction, locking the customer first:
   clear `display_name`, `phone`, `phone_digits`, `email`; empty `fields`; delete the customer's tags; clear `source` and
   `evidence_ref` on consent events (the rows stay as proof that a consent existed); clear `payload`, `source` and
   `activity_ref` on activities (the rows stay as typed, timed markers); set `external_ref` to `erased:<customerId>` and
   `status` to `ERASED`; bump `version`; append `customer.erased` to the outbox. A retry with the original ref finds the
   `erasure_requests` row and answers `200` with the same body; an unknown ref is `404`.
3. **Guards.** Writes to an `ERASED` customer (activities, orders, consents) answer `409 CUSTOMER_ERASED`. The same
   `external_ref` can be used afterwards and creates a new customer with a new id.
4. **Personal-data test.** `PersonalDataColumnsTest` reads `information_schema.columns` for every text, jsonb and array column
   in the crm schema and fails unless each is in either `PERSONAL_COLUMNS` (and cleared by the erasure test) or
   `NOT_PERSONAL_COLUMNS`, both lists in test resources, so a new column must be classified when it is added. The `outbox`,
   `event_inbox` and webhook delivery body columns are listed as "purged by housekeeping": confirm that the copied
   `MessagingHousekeeping` (`mailroom/.../jobs/MessagingHousekeeping.java` is the template) deletes relayed outbox rows, old
   inbox rows and delivered or dead webhook deliveries within a retention setting (default 7 days), add what the copy lacks,
   and test that a `customer.created` payload is gone from all three after the retention.
5. **Event contract.** `EventContractTest` builds each of the four events through the real publishers and validates `data`
   against `contracts/events/crm/*.schema.json`, and asserts the envelope (`id` UUID v7, `aggregate`, `schemaVersion` 2,
   `tenant-id`).
6. **Webhook contract.** `WebhookDeliveryContractTest` registers two endpoints of one Tenant subscribed to all four types,
   drives one of each through the real API, and asserts each endpoint receives each delivery once with valid `Crm-*` headers
   and signature, a body equal to the canonical envelope, and an `order.refunded` body that carries the `return` block. The
   second endpoint is the proof that a new Tenant-side consumer needs no crm change. An endpoint of another Tenant receives
   nothing.
7. **Logs.** A test greps the captured logs of the erasure and order tests for the sample name, phone and email and finds
   none.

## Verification

- `cd crm && ./gradlew build` green.
- Tests prove: after erasure no personal column holds data, orders and consent rows remain, `customer.erased` is delivered
  once per endpoint even when the call is retried 20 times in parallel, and the same `external_ref` starts a new customer.
- `git grep -n -i "loyalty" -- crm/apps crm/contexts crm/libs` finds nothing.

## Risks

- A new column added later can hold personal data silently: the column-classification test is the guard; do not weaken it.
- A Tenant that erases in crm and forgets loyalty leaves the member there. That is the Tenant's integration duty under the
  suite rule; `docs/crm.md` and `docs/loyalty.md` both say so.
- Backups and log aggregation hold personal data outside the app: documented in `docs/crm.md` as the Tenant's and operator's
  duty, not solved here.
