---
title: "One list-query contract across six apps"
date: 2026-11-16
summary: "Phases 4–7 of the list-query plan: every list of billing, mailroom, survey, attribution, notification and platform now filters, sorts and pages the same way, with proving indexes and suites"
---

# One list-query contract across six apps

## What happened

Finished `plans/261116-1657-list-query-contract` from phase 4 on. The shared modules were already in place:
`core-query` parses and validates a request, `core-spring` compiles it to jOOQ and supplies it to controllers,
`core-openapi` documents it. Each service now declares one `FilterSchema` per listing, binds it in its jOOQ adapter and
annotates the controller method with `@Listing`.

- **Billing** — customers, wallets, ledger, charges, reservations, payments, refunds, invoices, subscriptions, usage
  events, coupons, promotion codes, redemptions, webhook endpoints and deliveries, Stripe events, incidents, pricing
  plans and every portal list. New `GET /charges`, `GET /reservations`, `GET /refunds`; charges and refunds can be
  narrowed by their wallet's or payment's customer through a one-hop `exists` relation. `GET /customers` is no longer
  unbounded. The ledger uses the contract with `seq` as its order and `created_at` for a time window.
- **Mailroom** — templates, template revisions, campaigns, emails, receivers (`q` became `filter[email][contains]`),
  group members, groups, segments, suppressions. Suppressions use the normalized address as their tie-breaker because
  the table has no id.
- **Survey** — surveys, versions, links, responses, respondents, exports and both webhook lists. The fixed limits
  (survey 200, conversions 500 elsewhere) are gone; respondents return the contract's `{data,page}` and the total comes
  from `page_info=true`.
- **Attribution** — links, campaigns, goals, conversions. `conversions` no longer stops at 500.
- **Notification** — the inbox and the dispatch ledger. The inbox's `unread` flag became
  `filter[read_at][is_null]=true`; the old `unreadCount` disappeared with the envelope and is replaced by
  `page.total`.
- **Platform** — tenants, users, roles, tenant systems, credentials. A tenant-scope caller's listing is scoped from the
  actor, so a filter on another tenant can only narrow the page to nothing.
- The tenant Billing client now reads every list through the contract, following `next_cursor` page by page: plans,
  wallets, usage history, subscriptions and webhook endpoints joined payments and invoices.
- Each service got a migration with a full btree index leading every sortable column and a GIN trigram index for every
  `contains` field, plus a `ListingIndexIntegrationTest` that fails when a sortable or searched field is not covered.

## Defect found and fixed

`ListBinding.predicate` bound every filter value with the column type before looking at the operator, so
`filter[resolved_at][is_null]=true` tried to convert `true` to an `Instant` and failed at runtime. `is_null` is now
answered before any value is bound, with a `ListBindingTest` that renders both directions.

## Decisions

- **Usage aggregates stay a report.** It returns one total per metric over a required window, not rows of a resource;
  the contract's filter/sort/cursor model does not describe it. `docs/list-query-contract.md` records the exception.
- **Notification templates stay an array.** The list is derived from the notification-type enum, has no filterable or
  sortable columns and no meaningful page.
- **`is_null` and `neq`/`nin` follow SQL**, so they do not match null rows; a field offers only the operators its schema
  lists.
- **Every service generates its OpenAPI document.** Mailroom, survey and attribution were converted from hand-maintained
  skeletons to generation, like billing and notification already were: each `WebConfiguration` records the required
  permission and public flag from the same annotations the guards enforce, and each contract test compares
  `/v3/api-docs.yaml` with the committed file. A service behind a path prefix carries it in the server URL (for example
  `/mailroom`), so the paths are the ones the application actually serves.
- **Filter names are snake_case**, matching the schema and the columns; response bodies stay camelCase. The owner
  confirmed this after phase 3.

## Verification

- `core`: `:core-query:test`, `:core-spring:test`, `:core-openapi:test` green.
- Billing: `./gradlew :apps:app-boot:test` green, including OpenAPI snapshot and listing-index tests.
- Mailroom, survey, attribution, notification, platform: `./gradlew :apps:app-boot:test` green in each service.
- Tenant: full `./gradlew test` green; the Billing client test asserts cursor paging and encoded `filter[...]` paths.
- The sweep finds no remaining `before`/`after`/`afterSequence`/`customerId` row-list parameter and no unbounded
  `.fetch()` in a list read. The only remaining loose parameters are the usage aggregates report and the internal
  replica snapshot, both documented exceptions.

## Next steps

- Keep the generated documents in step: run the contract test with `-Dopenapi.update=true` after an intended API change
  and commit the result, in every service.
- The attribution catalogue holds permissions for spend and connection endpoints that do not exist yet; once those
  controllers land, the generated document will name them and the reverse catalogue check can be re-enabled.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
