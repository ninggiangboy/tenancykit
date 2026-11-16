---
phase: 8
title: "crm activities, 360 profile, search and export"
status: pending
priority: P2
effort: "M"
dependencies: [6, 7]
---

# Phase 8: crm Activities, 360 Profile, Search and Export

## Goal

Any system of the Tenant can write activities onto a customer's timeline, and the Tenant reads one customer as a single 360
profile, finds customers by search and filters, and exports them as CSV.

## Context

The `profile` context reads other contexts only through their `api` packages and writes nothing (the gate from phase 3). The
activity `type` is a free string chosen by the writer, so a future adapter posts a new type with no crm change. Migration range
`V40`. No aggregates (totals, lifetime spend, RFM) are computed: they are a non-goal.

## Files to create

- `crm/apps/app-boot/src/main/resources/db/migration/V40__activities.sql`; regenerate the jOOQ schema
- `crm/contexts/activity/...` (domain, application, api, adapter); `crm/contexts/profile/...` (query side only);
  search and export in `customers` (it owns the tables)
- Tests: `ActivityApiIntegrationTest`, `ProfileIntegrationTest`, `CustomerSearchIntegrationTest`, `CustomerExportTest`

## Tasks

1. **Activities.** `activities(tenant_id, id, customer_id, type, occurred_at, source, payload jsonb, activity_ref,
   created_at)`; `type` matches `^[a-z][a-z0-9._-]{0,63}$`, `source` at most 64 characters, `payload` a JSON object up to
   8 KiB (a setting), `activity_ref` optional and unique per Tenant (replay on the same body, `409` on a different one).
   `POST /activities` (customer by `externalRef`), `POST /activities/batch` (up to the cap, per-item outcomes), `GET
   /customers/{ref}/activities?type&from&to` newest first with keyset pagination. Index `(tenant_id, customer_id,
   occurred_at desc, id desc)`.
2. **360 profile.** `GET /customers/{ref}/profile`: the customer, its field values, tags, the current status per consent
   topic, the latest N orders (default 10, at most 50) with lines, and the latest N activities (default 20, at most 100).
   One call, bounded, no aggregates.
3. **Search and filter.** `GET /customers?q=&tag=&field.<key>=&field.<key>.gte|lte=&consent.<topic>=granted|revoked|none
   &createdFrom=&createdTo=&limit=&cursor=`. `q` matches `external_ref` exactly, a phone-digit prefix, an email prefix or a
   name (trigram or prefix, per the phase 5 decision). Several `tag` parameters mean all of them. `granted` and `revoked` read
   the latest consent event; `none` means no event. Keyset order `(created_at desc, id desc)`. Every filter uses an index; the
   test asserts the plans on a 100,000-customer fixture do not seq-scan the customers table for tag, field and consent filters.
4. **Export.** `GET /customers/export` takes the same filters and streams CSV (`text/csv`) with a server-side cursor and a
   bounded buffer: `externalRef, displayName, phone, email, tags (pipe separated), one column per active field key,
   createdAt`. Needs `crm:customer:export`. One export per Tenant at a time, held by a short lease; a second answers `429`.
   Cells beginning with `=`, `+`, `-` or `@` are prefixed with a quote so a spreadsheet cannot run them.
5. **Extension proof.** A test posts activities of two unrelated types (`pos.receipt-printed`, `app.opened`) and reads both
   back without any code change between them.
6. **Permissions.** `crm:activity:record` and `crm:activity:read`, `crm:customer:read`, `crm:customer:export`.

## Verification

- `cd crm && ./gradlew build` green.
- Tests prove: an activity replay by `activityRef` is idempotent; the profile contains exactly the requested slices; the
  filters combine correctly; the export of 100,000 customers streams within a fixed memory bound (assert peak heap growth);
  the spreadsheet-injection prefix is applied; tenant isolation holds for every new endpoint.

## Risks

- The profile is the one endpoint that joins several tables: keep each slice a bounded indexed query and test the worst case.
- Filters on custom fields depend on the GIN index and consistent value types: a type is immutable per key, which keeps this
  safe.
