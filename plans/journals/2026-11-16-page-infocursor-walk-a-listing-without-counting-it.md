---
title: "page_info=cursor: walk a listing without counting it"
date: 2026-11-16
summary: "page_info now takes cursor: page carries limit and next_cursor, total is null and no count runs"
---

# page_info=cursor: walk a listing without counting it

## Why

The review of the list-query contract recorded one deliberate trade-off: `next_cursor`
was only returned with `page_info=true`, which also counts every row the filter matches.
A client walking a large listing therefore paid one `count(*)` per page. The tenant
client's `readUpTo` did exactly that on every Billing listing it swept.

## What changed (`eb2e62c2`)

`page_info` now takes three values:

| Value | `page` carries | Count query |
|---|---|---|
| absent / `false` | nothing; `page` is null | none |
| `cursor` | `limit`, `next_cursor` (`total` null) | none |
| `true` | `limit`, `offset`, `total`, `next_cursor` | one per request |

- `PageFacts` (`NONE`, `CURSOR`, `FULL`) replaces the `boolean pageInfo` component in
  `ListQuery`, so the read path asks two questions (`included()`, `total()`) instead of
  inferring one from the other.
- `PageInfo.total` became `Long`: a cursor-only page must be able to say "not counted"
  rather than "zero", which is a real answer.
- `JooqLists` counts only for `FULL`; `ListQuery.emptyPage()` shapes an empty scope the
  same way, so a tenant-system caller with no mapped customers gets `total: null` in
  cursor mode.
- The OpenAPI parameter is now a string enum (`true`, `false`, `cursor`); the value on
  the wire did not change for existing callers. `total` is marked absentable with
  `offset` and `next_cursor` in both 3.0 and 3.1 forms.
- The tenant client walks with `page_info=cursor`. Its test now serves cursor-only
  pages (no `total`, no `offset`), which fails if the client ever depends on the count.
- `docs/list-query-contract.md` documents both modes and recommends `cursor` mode for a
  walk.

## Verification

- `core`: `:core-query:test`, `:core-spring:test`, `:core-openapi:test` — the new
  `JooqListsTest.askingOnlyForTheCursorSkipsTheCount` asserts exactly one SQL statement
  (no count) and a null `total`.
- tenant: `:contexts:billing-client:test`.
- notification: `ListingQueryTest`, including the new cursor-only HTTP case.
- billing: invoicing, payment, pricing/usage and webhook delivery HTTP tests.
- platform: `ListingQueryTest`.
- OpenAPI regenerated for billing, mailroom, survey, notification and platform. The
  notification contract test reached the compose broker on `localhost:5672` (started by
  a concurrent session) instead of its own, so that run was given a throwaway RabbitMQ
  container through the app's env properties; the container was removed afterwards.

## Left alone

`contracts/openapi/attribution.yaml` is not in this commit: another session is changing
that app, and its document will be regenerated there. Its committed contract stays one
shape behind until that lands.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
