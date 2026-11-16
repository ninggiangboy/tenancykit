---
title: "Naming the page_info values: full and cursor"
date: 2026-11-16
summary: "page_info takes full or cursor; omitting it means no paging facts, and the boolean leftover true is refused"
---

# Naming the page_info values: full and cursor

## Why

The previous commit made `page_info` an enum by adding `cursor` beside `true`, which
left a boolean word inside a parameter that now has named modes. `page_info=full` says
what the mode is; `true` only says it is not false.

## What changed (`49972e59`)

| `page_info` | `page` carries | Count query |
|---|---|---|
| absent | nothing; `page` is null | none |
| `cursor` | `limit`, `next_cursor` (`total` null) | none |
| `full` | `limit`, `offset`, `total`, `next_cursor` | one per request |

- The parser accepts `full` and `cursor`; `true`, `false` and every other value are a
  400: `the parameter page_info takes full or cursor`. No alias is kept — the
  contract's rule is one spelling per meaning, and the value existed only inside this
  repository for a few hours.
- The OpenAPI parameter is a string enum of the two values with no default; its
  description says that omitting it returns the rows alone, so a generated client has
  no reason to send `false`.
- Docs and the phase plan now record the two values, and the app integration tests
  that ask for every fact send `full`.
- The five committed documents were regenerated. Attribution's stays with the session
  that is changing that app.

## Verification

- `core`: `:core-query:test`, `:core-spring:test`, `:core-openapi:test` — the parser
  test asserts `full` and `cursor` are accepted and `true` is refused with the message
  naming both.
- tenant: `:contexts:billing-client:test` (it already walks with `cursor`).
- notification: `ListingQueryTest`, contract test (with a throwaway RabbitMQ container
  because the compose broker on localhost:5672 belongs to another session).
- platform: `ListingQueryTest`.
- billing: invoicing, payment, pricing/usage and webhook delivery HTTP tests; the OpenAPI
  contract test ran during regeneration.
- OpenAPI regenerated for billing, mailroom, survey, notification and platform.

## One failure that is not ours

`WebhookDeliveryIntegrationTest.concurrentWorkersNeverDeliverTheSameRowTwice` fails in
this environment with 35 of 60 claims. It fails identically at `926688b4` in a clean
worktree, before any `page_info` change, so it is a pre-existing load-sensitive test
rather than a regression of this work. It was left untouched.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
