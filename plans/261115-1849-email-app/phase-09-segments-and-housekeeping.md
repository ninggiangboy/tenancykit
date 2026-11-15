---
phase: 9
title: "Dynamic segments and housekeeping"
status: done
priority: P1
effort: "M"
dependencies: [7]
---

# Phase 9: Dynamic Segments and Housekeeping

## Goal

A campaign can target a **segment**: a saved filter over receiver attributes, evaluated when the campaign snapshot is
taken. Rendered bodies and tracking links are removed after the retention period while metadata remains.

## Segments (context `audience`)

- `segments(id, tenant_id, name, filter jsonb, version, created_at, updated_at, unique (tenant_id, name))`
- Filter AST (JSON), validated at write time and again before evaluation:

```json
{ "and": [
    { "field": "country", "op": "eq", "value": "VN" },
    { "or": [ { "field": "plan", "op": "in", "value": ["pro", "team"] },
              { "field": "trial_days", "op": "lt", "value": 7 } ] },
    { "not": { "field": "churned", "op": "exists" } } ] }
```

- Operators: `eq`, `neq`, `in`, `gt`, `gte`, `lt`, `lte`, `exists`, `and`, `or`, `not`; plus the built-in fields `email_domain`,
  `created_at`, `status`, and `group` (`op: "in_group"`). Depth ≤ 4, ≤ 20 predicates, values typed and bounded.
- Compilation produces **parameterised SQL** over `attributes` (`attributes ->> :key`, with a typed cast for numeric
  comparisons) and always adds `tenant_id = :tenant`, `status = 'SUBSCRIBED'` and the marketing suppression anti-join.
  No expression text is ever concatenated; field names are checked against `^[a-z][a-z0-9_]{0,31}$` and bound as parameters.
- `POST /segments/{id}/preview` returns a count (capped) and a small sample, and an `EXPLAIN` guard test proves the plan
  uses an index for indexed shapes. A per-campaign maximum audience bounds a pathological filter.
- A campaign's `target_type = 'SEGMENT'` evaluates the filter once, when the snapshot is built.

## Housekeeping (worker job, per tenant)

- Null `body_html`, `body_text`, delete `email_links` and set `body_purged_at` for emails older than the tenant's
  `body_retention_days` (default 30). `GET /emails/{id}` then returns metadata and events with `body_purged: true`.
- Clean `event_inbox` (30 days) and relayed `outbox` rows the way `notification`'s `MessagingHousekeeping` does.
- Delete `campaign_recipients` for completed campaigns after a longer configured period; keep `campaigns` and stats.
- Switches mirror mailer's: `email.jobs.enabled`, `email.housekeeping.enabled`; neither drops queued work.

## Files to create

- `mailroom/contexts/audience/.../segment/*` (aggregate, filter AST, validator, SQL compiler, preview)
- `mailroom/contexts/sending/.../campaign/` (snapshot builder reads the audience `api` port `SegmentResolver`)
- `mailroom/apps/app-boot/.../jobs/HousekeepingJob.java`, `MessagingHousekeeping.java`
- `mailroom/apps/app-boot/src/main/resources/db/migration/V7__segments.sql`; OpenAPI additions
- Tests under `.../audience/segment/` and `.../jobs/`

## Verification

- Compiler tests for every operator and combination; injection tests (field names and values containing quotes, `;`,
  comment markers) produce a validation error or an inert parameter, never SQL.
- Cross-tenant test: a filter can never return another tenant's receivers (RLS plus the explicit predicate).
- Performance test with 200,000 receivers: indexed shapes complete within the budget recorded in the test; an
  unindexable shape is rejected or capped.
- Housekeeping integration test: bodies older than retention are removed, newer ones kept, metadata and events intact,
  and the job is idempotent.

## Risks

- A filter language grows in the user's mind ("just add regex"); the closed operator set is the contract, and widening it
  needs a new decision.
- Purged email links make an old click impossible; the "link expired" page is the documented behaviour.
