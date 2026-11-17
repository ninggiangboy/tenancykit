# CRM

The suite's app for keeping a **Tenant's own customer data**: who its customers are, what they bought, what they did and
what they consented to. A Tenant's system sends customers, orders with line items, returns and activities; crm stores
them, serves a 360 profile with search, filters and a CSV export, and sends signed webhooks of what changed. It is a
separate deployable (`crm/`) with the suite's three runtimes: `migrate`, `api` and `worker`.

**Status: shipped.** `crm/` builds, migrates and tests green against the suite's own infrastructure, and every endpoint and
webhook below exists in it. This document describes what it is; the [API document](../contracts/openapi/crm.yaml) and the
[event schemas](../contracts/events/crm/) are the shapes a client builds against.

crm consumes another app's business data in no case. It keeps a replica of Platform and reads nothing from Billing,
mailroom, survey or attribution; it never calls, listens to or names loyalty.

## What it is, and what it is not

| It is | It is not |
|---|---|
| The owner of a Tenant's customers, orders with lines and returns, activities, custom fields, tags and consent | A CRM product: no case, complaint or ticket handling, no analytics, no segments and no sales pipeline |
| A producer of four signed webhooks — `customer.created`, `order.recorded`, `order.refunded`, `customer.erased` — to a Tenant System | A publisher of an AMQP exchange: there is no `crm.events`, and no other app binds to a crm fact |
| A REST API a Tenant System drives directly, one record at a time or in batches | A UI: the Console and any customer screen are a separate follow-up |
| A store of a Tenant's customer data on the Tenant's behalf, with an explicit erasure endpoint | A customer-identity system that matches a person by phone or email, or joins data to another app |

The Tenant's data is the Tenant's: another Tenant's token cannot read or write it, and no other app reads it.

## Vocabulary

| Term | Meaning |
|---|---|
| Customer | One of a Tenant's customers: an `externalRef` the Tenant chose, plus the tenant-scoped attributes the Tenant sent. Identified by `(tenant, external_ref)` |
| `externalRef` | The Tenant's own customer key. Chosen by the Tenant's system, unique per Tenant, immutable, and the same value the Tenant uses in the other app if it uses both |
| Order | One purchase, recorded once: `orderRef`, `currency`, `occurredAt`, `storeCode`, `channel`, `total` and lines. Never changed after it is recorded; a change of what was bought is a return |
| Order line | One sold item: `lineNo`, `sku`, `name`, `category`, `quantity` (3 decimals), `amount` (net of discount) and `earnEligible` (default true) |
| Return | One return against an order: its own `returnRef`, `occurredAt` and lines with a quantity and an amount. Returned quantity and amount never exceed what was sold |
| Activity | One thing the customer did that the Tenant wants in the 360 view: a `type` string and a payload, with an optional `activityRef` for idempotency |
| Custom field | A Tenant-defined customer attribute, with a key, a type and whether it is required |
| Tag | A Tenant-defined label attached to a customer; `addTags` and `removeTags` change the set |
| Consent topic | A Tenant-defined purpose a customer consents to, with the state and the time it changed |
| 360 profile | One customer read back as one document: the customer, its tags, its custom fields, its orders, its activities and its consent state |
| Batch item outcome | `CREATED`, `UPDATED`, `UNCHANGED`, `REPLAYED` or `FAILED` with a code, reported per item of a batch call |
| `orderVersion` | A counter that rises with every return on an order. Two order webhooks carry it so a receiver that keeps order state can drop an older delivery |
| Erasure | The endpoint that clears a customer's personal data and leaves an anonymous row, so the Tenant can prove the person is gone |

## Who owns what

**crm**
- Owns customers, their custom fields, tags and consent, orders with lines and returns, activities, the 360 profile, the
  CSV export and the erasure of all of it.
- Owns its webhook endpoints, its signed deliveries and its four-type event catalog.
- **Enforces** `crm:*` permissions from its replica of Platform data; it never mints, edits or decides them. Every code
  is tenant scope and every code is grantable to a Tenant System, which is the caller that drives the app.
- Guarantees tenant isolation and per-item idempotency: a repeated natural key is a replay, not a second record.

**Platform**
- Owns tenants, domains, users, access, entitlement and the suite's tokens. crm keeps a replica of the five tables it
  enforces and reads no other Platform state.

**The Tenant System**
- Owns the point of sale and the web systems that produce customers, orders, returns and activities, and the
  `externalRef` values that name them.
- Owns what it does with crm's webhooks, and — if it also uses loyalty — owns the connection between the two apps
  (see *The suite rule*).

**Division rules**
- The tenant comes from the verified host, never from client input.
- Money is an integer in minor units with an ISO 4217 currency.
- crm never reaches into another app; another app never reaches into crm.

## Runtimes and contexts

| Runtime | Does |
|---|---|
| `migrate` | Runs Flyway as the table owner and exits. Nothing else starts. |
| `api` | Serves `/api/v1/...` behind Caddy's `/crm` prefix. No job and no Platform listener runs here; readiness is `DOWN` until the worker has finished one replica rebuild. |
| `worker` | Consumes `crm.platform-events` on its own queue, runs the in-process outbox relay, the webhook delivery round and housekeeping. |

| Context | Owns |
|---|---|
| `customers` | `customers`, `field_definitions`, `customer_tags`, `consent_topics`, `consent_events`, `erasure_requests`; search, filter and the CSV export |
| `orders` | `orders`, `order_lines`, `order_returns`, `order_return_lines` |
| `activity` | `activities` |
| `profile` | Nothing: a read model that reaches the other three through their `api` packages |
| `webhook` | `webhook_endpoints`, `webhook_deliveries`, `webhook_delivery_attempts`; signed delivery |
| `platform` | The replica of Platform's five tables and `replica_state` |

The architecture gates hold those seams in the build: the profile writes no table, orders and activities reach customers
only through its `api` package, no business context imports the webhook context (it listens to the outbox instead), and
no class names another app or a foreign exchange.

## Settings that change behaviour

All are deployment settings, not per-Tenant ones. The defaults are the ones a fresh install gets.

| Setting | Default | What it bounds |
|---|---|---|
| `crm.batches.max-customers`, `max-activities` | 1,000 | Items one batch call may carry |
| `crm.batches.max-orders` / `max-order-lines` | 100 / 5,000 | Orders and their total lines in one batch call |
| `crm.orders.max-lines-per-order` | 500 | Lines in one order, and so the size of one outbox row and webhook body |
| `crm.orders.max-future-skew` | 5 minutes | How far ahead of the clock an order or return may be dated; there is no lower bound, so historic imports are allowed |
| `crm.fields.max-active-definitions` / `max-bytes` | 50 / 16 KiB | One Tenant's active field definitions and one customer's serialized field values |
| `crm.tags.max-per-customer` | 50 | Tags on one customer |
| `crm.activity.max-payload-bytes` | 8 KiB | One activity payload |
| `crm.housekeeping.*-retention` | 7 days | How long an inbox id, a relayed outbox payload, a dead outbox row and a finished delivery with its attempts are kept |

Raising the order line cap raises the webhook body with it; a Tenant whose endpoint is slow should lower it rather than
raise its own timeouts.

## Identity

- A customer is `(tenant, external_ref)`. The ref is the Tenant's own key: chosen by the Tenant's system, unique per
  Tenant and immutable. The Tenant uses the same ref in both apps if it uses both.
- crm generates its own UUID v7 surrogate id for the customer and never stores another app's id.
- crm never matches a customer by phone or email and never merges two customers automatically. Phone and email are
  stored attributes with a plain index for search, nothing more.
- An erased customer's `external_ref` becomes `erased:<id>`, so the same ref can later start a new customer.
- A customer must exist before an order names it. The order endpoint answers `422 CUSTOMER_UNKNOWN` unless the request
  sets `createCustomer: true`, which upserts a customer with that `externalRef` and no other data.

## Ingest and idempotency

crm's single and batch endpoints are idempotent by natural key, so a retry after a timeout is always safe:

| Resource | Natural key |
|---|---|
| Customer | `external_ref` |
| Order | `orderRef` |
| Return | `returnRef` |
| Activity | an optional `activityRef` |

- A repeated key with the same body replays the stored result and answers `200`; with a different body it answers `409`.
- A batch reports each item (`CREATED`, `UPDATED`, `UNCHANGED`, `REPLAYED`, `FAILED` with a code) and never fails as a
  whole because one item is bad.
- The defaults are settings: 1,000 customers or activities per batch; 100 orders per batch with at most 5,000 lines
  together; 500 lines per order; an activity payload up to 8 KiB.
- The upsert and the patch are conditional: `PUT /customers/{externalRef}` answers an `ETag`, and `PATCH
  /customers/{externalRef}` requires `If-Match` and answers `412` on a stale tag (`428` without one).

## Order shape

An order carries `orderRef`, `externalRef`, `currency`, `occurredAt`, `storeCode`, `channel`, `total` and lines
`{sku, name, category, quantity (3 decimals), amount (net of discount), earnEligible (default true)}`.

- `total` and every line amount are integers in minor units.
- A return names the order and its lines with a quantity and an amount. A returned quantity or amount that exceeds what
  was sold is `422`.
- The shape is a shared contract, never shared code: loyalty takes the same fields, without `name` and `total`, which it
  does not read. A Tenant's forwarder maps a crm order webhook onto a loyalty call field for field.

## Webhooks

crm sends four signed webhooks to the Tenant's subscribed endpoints. Each is written to the outbox in the same
transaction as the change and turned into a delivery in process by the webhook context; no `crm.events` exchange exists
and no other app consumes a crm fact.

| Type | Payload (`data`) |
|---|---|
| `customer.created` | `{customerId, externalRef, createdAt}` |
| `order.recorded` | the order's full current state: `{customerId, externalRef, orderRef, orderVersion, currency, total, occurredAt, storeCode, channel, lines:[{lineNo, sku, category, quantity, amount, earnEligible, returnedQuantity, returnedAmount}]}` |
| `order.refunded` | the same full order state, plus `return: {returnRef, occurredAt, lines:[{lineNo, quantity, amount}]}` |
| `customer.erased` | `{customerId}` |

- `aggregate` is `customer/<id>` or `order/<orderRef>`. The envelope's `id` is the deduplication key.
- The two order events both carry the order's **complete current state**, with cumulative returned quantity and amount,
  and an `orderVersion` that rises with every return. `order.refunded` also carries the return that caused it, so a
  forwarder needs no other call back to crm.
- `orderVersion` is monotonic per order, so a receiver that keeps the order's state can drop an older delivery by the
  same rule Platform replicas use.
- Signing, the destination policy, the guarded DNS resolution, the HTTP send and the retry schedule are the shared
  `core-webhook` mechanics Billing and Survey also use; the endpoints, the delivery state and the event catalog stay in
  crm. Success is any 2xx; a failed delivery retries, goes `DEAD` when its budget is exhausted and can be retried.

## Erasure

`POST /customers/{externalRef}/erasure` is idempotent. It clears every personal field of the customer, its activities
and its consent contact data, leaves the customer row with `external_ref` set to `erased:<id>`, and sends
`customer.erased` to the Tenant's endpoints. The erasure record stores a hash of the ref, never the ref itself.

Each app erases on its own endpoint. Erasing a person in crm does not erase them in another app. A Tenant that uses both
crm and loyalty must erase in both, or forward `customer.erased` to loyalty; the same duty holds the other way. A Tenant
that keeps no copy in its own systems can treat the two erasure endpoints as its complete story.

## Export

`GET /customers/export` streams the same filters as the list as `text/csv`, through a server-side cursor and a bounded
buffer. One export runs per Tenant at a time, held by a short lease; a second answers `429`. A cell beginning with `=`,
`+`, `-` or `@` is prefixed with a quote, because a spreadsheet would otherwise run it as a formula.

## Runbook

**A delivery is `DEAD`.** `GET /webhook-deliveries?status=DEAD` lists what gave up and why in `lastError`, with every
attempt and its status code. Fix the endpoint, then `POST /webhook-deliveries/{id}/retry`: the original stays as evidence
and the retry is its own delivery.

**Deliveries are slow or piling up.** The relay and the sender each poll on a setting; a large batch of orders on a slow
endpoint shows as `attempts` rising against one endpoint. An endpoint can subscribe to only the types it needs, which is
the first thing to narrow.

**An order is larger than a delivery.** Raise `crm.orders.max-lines-per-order` only after confirming the endpoint accepts
the body; the line cap is also the cap on the row the relay copies.

**An activity payload is refused at 8 KiB.** Raise `crm.activity.max-payload-bytes` or send the detail as a field value
and a small payload that points at it.

**An export answers `429`.** Another export of that Tenant is still running; it releases its lease when it finishes, or
when the lease runs out if the instance died.

**A post-erasure support question.** The business rows remain: an order, a consent history and a typed, timed activity
still exist without the person in them. The `erasure_requests` row proves the call happened, and holds only a hash of the
reference. Backups and log aggregation are outside the app — they are the operator's retention duty, not something
erasure can reach.

## The suite rule

crm never calls, listens to or knows loyalty. Nothing crm does needs loyalty, and no loyalty fact reaches crm. This keeps
crm optional: a Tenant can pair it with its own systems, another vendor's, or nothing at all.

A Tenant that uses both apps connects them itself, in one of two ways:

- **Send twice.** Send each order and return to both apps. Both are idempotent by the same `orderRef` and `returnRef`, so
  a retry after a failed call is safe; the same order earns the same points at either route. A Tenant that does this must
  also erase in both, because each app erases only what it holds.
- **Forward.** Register an endpoint for crm's webhooks and forward `order.recorded`, `order.refunded` and
  `customer.erased` to the other app. Because the request bodies use the same field names, the forwarder is a
  field-for-field mapping and needs no other read of crm. Answer loyalty's `422 ORDER_UNKNOWN` to a forwarded return with
  a non-2xx so crm's retry schedule delivers it again once the order has arrived.

Both recipes are proved end to end by `ops/smoke/crm-loyalty.sh` on the compose stack, which plays the Tenant System on
each route and compares the points it gets.

## Non-goals

- Case, complaint and ticket handling; analytics, RFM and saved segments; sales pipeline, leads and deals.
- Reading any event or business data from Billing, mailroom, survey or attribution. Those apps may post to the activity
  API later through an adapter this document does not cover.
- Automatic merging of customers by phone or email, and any customer-identity resolution across apps.
- Any UI or Console page; the app is API only.
- A `crm.events` exchange, and any other app binding to a crm fact. crm's facts leave only as signed webhooks.
- Matching a crm customer to a Billing customer. The Tenant System owns identity across the suite.
- Any link between crm and loyalty: no call, no event, no shared table and no shared code.
- Integrating crm into the sample Tenant System, and migrating existing data from an earlier layout.

## Documents this app owns

| Document | Owns |
|---|---|
| [contracts/permissions/crm.yaml](../contracts/permissions/crm.yaml) | The app's permission catalog: every `crm:*` code, tenant scope and grantable to a Tenant System |
| [contracts/openapi/crm.yaml](../contracts/openapi/crm.yaml) | The REST surface |
| [contracts/events/crm/](../contracts/events/crm/) | The four webhook payload schemas, one per type |
