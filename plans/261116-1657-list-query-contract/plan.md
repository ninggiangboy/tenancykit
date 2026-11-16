---
title: "One list-query contract (filter, sort, paging) for every list API"
description: "A shared core-query module and its jOOQ, Spring and OpenAPI bindings give every list endpoint of billing, mailroom, survey, attribution, notification and platform the same whitelisted filter, multi-field sort, offset and cursor paging."
status: done
priority: P1
effort: "7 phases, 1 × S, 3 × M, 3 × L"
branch: dev
tags: [feature, backend, api, database]
blockedBy: []
blocks: []
created: 2026-11-16
---

# One list-query contract

## Overview

The Tenant System could not filter enough: billing `GET /customers` loads every customer, charges and reservations
cannot be listed or found by `reference`, the ledger only pages by sequence, the tenant client reads only the first
page of invoices, payments, subscriptions and usage, and three different paging styles (`before`, `after`, `offset`)
coexist. This plan replaces every list endpoint's ad-hoc parameters with one contract.

Scope was fixed in the `/ak-advise` interview of 2026-11-16. The advice was delivered in the conversation only; this
plan carries every confirmed decision.

## Outcome contract

**Goals**

1. A Tenant System filters, sorts and pages any list endpoint with one syntax and finds anything it needs to
   reconcile, in every app already built. CRM and loyalty declare a schema and get the same.
2. No list is unbounded or capped without a way to read the rest.
3. The OpenAPI document and the docs always describe the real contract, because both are generated from the schemas.

**Contract (confirmed)**

- Syntax: `filter[<field>][<op>]=<value>`, conditions joined with AND; one OR level:
  `filter[or][<i>][<field>][<op>]=<value>` (branches with different `i` are OR-ed, predicates sharing an `i` are AND-ed).
- Operators `eq neq in nin gt gte lt lte is_null`, applied per type. Text also has `starts_with` and `contains`
  (case-insensitive, `%`/`_`/`\` escaped); `contains` is allowed only for a field declared with a trigram index.
- Types: string, uuid, enum (validated against the declared values), long, decimal, boolean, instant.
- Whitelist per resource: API name, type, operators, sortable, nullable, optional one-hop relation
  (`wallet.customer_id`, compiled to `exists(subquery)`). Internal columns are never exposed. Unknown field, operator
  or value is a 400.
- Sort `sort=-created_at,total`: up to 4 whitelisted fields, `id` appended as tie-breaker; sortable fields must be
  NOT NULL columns and never relations.
- Paging: `limit` (max 200) with either `offset` (max 10 000) or an opaque `cursor`; never both. A cursor carries the
  last sort values, the last id and a hash of filter+sort, and is refused with another filter or sort.
- Response is always `{data, page}`; `page` is `null` unless the caller sends `page_info`, then
  `{limit, offset, total, next_cursor}` for `page_info=full` and `{limit, next_cursor}` for `page_info=cursor`, which
  does not count the matching rows.
- Limits: 20 predicates, 100 values per `in`/`nin`, 10 OR branches.
- Old parameters are removed with no alias.
- Billing gains `GET /charges`, `/reservations`, `/refunds`; the ledger uses the contract.
- Non-goals: JSON attribute filters, replacing mailroom `SegmentFilter`, cross-app filters, full-text search or a
  read store, `POST /search`, CRM and loyalty themselves.

**Constraints**: RLS and tenant-system scoping still apply; parameterised SQL only; write side untouched; schema
declarations stay out of jOOQ-dependent layers per the ArchUnit rules; documentation and OpenAPI are part of done.

**Done means**: see each phase; overall — every list endpoint of the six apps and the tenant client uses the
contract, no old parameter or unbounded list remains, indexes exist for every sortable/contains field and are proved
by a test per app, OpenAPI is generated and snapshot-tested per app, `docs/list-query-contract.md` exists and every
suite passes.

## Phases

| # | Phase | Status |
|---|---|---|
| 1 | [core-query: schema, parser, validator, cursor, page](phase-01-core-query.md) | done |
| 2 | [core-spring: jOOQ compiler, argument resolver, errors, OpenAPI](phase-02-core-spring-bindings.md) | done |
| 3 | [Billing pilot: payments and invoices, tenant client](phase-03-billing-pilot.md) | done |
| 4 | [Billing: remaining lists, portal, new endpoints](phase-04-billing-rest.md) | done |
| 5 | [Mailroom, survey, attribution](phase-05-mailroom-survey-attribution.md) | done |
| 6 | [Notification, platform](phase-06-notification-platform.md) | done |
| 7 | [Contract doc, app docs, sweeps, journal](phase-07-docs-and-sweeps.md) | done |

## Open items

- **Usage aggregates** stays a report with its required `customerId`/`from`/`to` window rather than a row listing; the
  contract document records the exception. The owner approved this exception.

Every service now generates its OpenAPI document from the controllers and compares it with the committed file. An
intended API change is regenerated with `-Dopenapi.update=true` and committed, exactly as billing did.

## Verified evidence

- Billing `GET /customers` runs `selectFrom(CUSTOMERS)...fetch()` with no limit; attribution `GET /conversions` is
  fixed at `limit(500)` with no cursor; survey `GET /surveys` is fixed at 200 with no cursor.
- `BillingFeignApi` passes no `before`, so the tenant reads only the first page.
- `charges` and `reservations` carry a `reference` column; nothing lists or filters by it.
- Each app already has springdoc in its `app-boot` build; whether `/v3/api-docs` is exposed is checked in phase 2.

## Decisions taken while planning

- The keyset predicate is written by hand (OR-chain of `>`/`<` by direction) instead of jOOQ `seek()`, so mixed
  asc/desc order is deterministic and its SQL is unit-testable without a database.
- The OpenAPI customizer lives in its own `core-openapi` module, not in `core-spring`: `core-spring` may see only the
  framework and jOOQ (ArchUnit `CoreSpringBoundaryTest`), and springdoc is neither.
- `PageInfo`'s last component is named `next_cursor` so the JSON matches the confirmed contract without a serializer
  annotation in a plain-Java module.
- `core-query` is pure Java next to `core-kernel` (depends only on it); everything that needs jOOQ or Spring lives in
  `core-spring`.
