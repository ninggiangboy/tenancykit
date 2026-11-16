---
phase: 7
title: "crm orders, lines, returns and order events"
status: pending
priority: P1
effort: "L"
dependencies: [5]
---

# Phase 7: crm Orders, Lines, Returns and Order Events

## Goal

The Tenant's POS records an order with its lines and later returns, idempotently by its own references, and every recorded
order or return is delivered to the Tenant's webhook endpoints as a full-state event, which a Tenant may forward to loyalty
(or anywhere else) without reading crm again.

## Context

Order and return idempotency is by natural key (`orderRef`, `returnRef`) with `Fingerprint` comparison
(`core/core-kernel/.../idempotency`): same key and body replays the stored answer, same key and a different body is `409`.
Order corrections are not supported: a mistake is fixed by a return. Migration range `V30`. The event shape is fixed by
`contracts/events/crm/order.*.schema.json` (phase 1). loyalty's order API uses the same field names, and its earn base reads
`earnEligible` and `amount` per line, so those fields are required and exact here: a Tenant that forwards crm's webhook to
loyalty must get the same points as one that calls loyalty directly.

## Files to create

- `crm/apps/app-boot/src/main/resources/db/migration/V30__orders.sql`; regenerate the jOOQ schema
- `crm/contexts/orders/...` features `order`, `return` (domain, application, api, adapter), an outbox publisher
  `OrderEventPublisher`
- Tests: `OrderApiIntegrationTest`, `OrderReturnIntegrationTest`, `OrderBatchIntegrationTest`, `OrderEventSizeTest`,
  `OrderIsolationTest`

## Tasks

1. **Tables.** `orders(tenant_id, id, order_ref, customer_id, currency, total, occurred_at, store_code, channel, status
   RECORDED|PARTIALLY_RETURNED|RETURNED, version, fingerprint, created_at)` unique `(tenant_id, order_ref)`;
   `order_lines(order_id, line_no, sku, name, category, quantity numeric(12,3), amount bigint, earn_eligible,
   returned_quantity, returned_amount)`; `order_returns(tenant_id, id, order_id, return_ref, occurred_at, fingerprint)` unique
   `(tenant_id, return_ref)` and return lines. All with RLS; money in minor units; `currency` is a 3-letter code. The order
   `total` is informational (tax, delivery and order-level discounts make it differ from the line sum); earn reads lines only.
2. **Record.** `PUT /orders/{orderRef}`: 1-500 lines (a setting), non-negative amounts, positive quantities, `earnEligible`
   defaults to `true`, `occurredAt` at most 5 minutes in the future and otherwise unbounded (historic imports are allowed).
   The customer is looked up through `customers.api` by `externalRef`; unknown is `422 CUSTOMER_UNKNOWN` unless the body has
   `createCustomer: true`, which creates a customer with that ref and nothing else (and its `customer.created` event) in the
   same transaction. `201` on create, `200` on an identical replay, `409` on a different body.
3. **Return.** `PUT /orders/{orderRef}/returns/{returnRef}` with lines `{lineNo, quantity, amount}`. Lock the order row first;
   cumulative returned quantity and amount may not exceed what was sold (`422 RETURN_EXCEEDS_SALE`); update the lines, the
   status and the order `version` (version 1 is the recording, each return adds one). Same `returnRef` and body replays.
4. **Events.** `order.recorded` at version 1 and `order.refunded` at each later version, both with the complete current state
   (lines with cumulative returned figures) and `orderVersion`; `order.refunded` also carries the `return` that caused it
   (`returnRef`, `occurredAt`, its lines). Appended to the outbox in the transaction of the change.
5. **Reads.** `GET /orders/{orderRef}` (lines, returns, version, `ETag`), `GET /customers/{externalRef}/orders` (newest first,
   keyset pagination).
6. **Batch.** `POST /orders/batch`: up to 100 orders and 5,000 lines in total, per-order transactions, per-item outcomes
   `CREATED|REPLAYED|FAILED` with a code.
7. **Size.** `OrderEventSizeTest` records a 500-line order, relays it through the real outbox relay and webhook delivery to a
   local receiver, and asserts the delivery arrives, verifies its signature and validates against the schema. If it does
   not, lower the line cap and record the measured limit in `docs/crm.md` and the plan's open question.
8. **Permissions.** `crm:order:record` to write, `crm:order:read` to read.

## Verification

- `cd crm && ./gradlew build` green.
- Tests prove: an order replay is idempotent and emits one event; a changed body is `409`; 50 parallel identical `PUT`s create
  one order and one event; a return over the sold quantity is refused and leaves the order unchanged; two parallel returns of
  the last unit cannot both succeed; the order event contains cumulative returned figures after the second return; a batch
  reports each order; tenant B cannot see tenant A's orders or customers through an order endpoint.

## Risks

- A large order makes a large outbox row and webhook body (see task 7). The cap is a setting, not a constant.
- `createCustomer: true` hides typos in `externalRef`; documented, and off by default.
