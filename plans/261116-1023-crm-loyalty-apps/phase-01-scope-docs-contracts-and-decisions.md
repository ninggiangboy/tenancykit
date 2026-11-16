---
phase: 1
title: "Scope, docs, contracts and decisions"
status: pending
priority: P1
effort: "M"
dependencies: []
---

# Phase 1: Scope, Docs, Contracts and Decisions

## Goal

The two apps exist as written contracts: scope docs, permission catalogs, OpenAPI files, the four crm webhook payload schemas
and the earn test vectors, so every later phase builds against a fixed, reviewable surface and no Java is written yet. The
suite rule that apps never consume each other's business events is written into the shared contracts and docs, so this plan
and every later app build against it.

## Context

Both apps are driven by the Tenant's own system with a Tenant System credential (`contracts/tokens/README.md`). Follow the
shape of `contracts/permissions/mailroom.yaml` (codes, `scopes`, `grantable_to_tenant_system`) and the paths of
`contracts/openapi/mailroom.yaml` (`/<app>/api/v1/...`). Event schemas follow `contracts/events/survey/*.schema.json`, which
serve Survey's signed webhooks, and the envelope in `contracts/messaging/README.md`. The webhook endpoint surface and the
`survey:webhook:manage` code follow `contracts/openapi/survey.yaml` (`/webhook-endpoints`, `/webhook-deliveries`) and
`contracts/permissions/survey.yaml:53-56`. The vector layout follows `contracts/attribution/attribution-vectors/` and
`contracts/survey/` if they exist when this phase runs (read them; do not edit them).

The suite rule this phase writes down: the broker carries Platform's replicated data, infrastructure commands and outcomes
(`mailer.commands`, `mailer.events`) and an app's traffic with itself (Billing consumes its own `billing.events` in its
invoicing cycle). No app binds a queue to another app's business exchange. A fact a Tenant's own system reacts to leaves
the producing app as a signed webhook to that Tenant System, and the Tenant System is the only party that connects two
apps. Today's apps already obey it in code; only the wording "so a future app can react" contradicts it
(`docs/suite-messaging.md:39-40`, `docs/survey.md:59`, the `billing.events` and `survey.events` rows of the Topology table
in `contracts/messaging/README.md`).

## Files to create

- `docs/crm.md`, `docs/loyalty.md`: scope docs (purpose, vocabulary, who owns what, non-goals, decisions from the plan's
  Key decisions table, status "planned"). Each stays under 800 lines.
- `contracts/permissions/crm.yaml`, `contracts/permissions/loyalty.yaml`
- `contracts/openapi/crm.yaml`, `contracts/openapi/loyalty.yaml`
- `contracts/events/crm/{customer.created,order.recorded,order.refunded,customer.erased}.schema.json`
- `contracts/events/loyalty/*.schema.json`: the twelve types of the plan's "loyalty webhooks" decision
- `contracts/loyalty/earn-vectors/vector.schema.json` and at least 12 vector files (see task 5)

## Files to modify

- `contracts/README.md` (index rows for the new folders), `contracts/events/README.md` (a `crm/` row and a `loyalty/` row:
  producer crm or loyalty, signed HTTP to Tenant Systems only, no AMQP)
- `contracts/messaging/README.md` (the rule as its own section; the Topology rows for `billing.events` and `survey.events`
  say "consumed by its own app only" and "no consumer; no other app may bind to it" instead of inviting a future consumer)
- `docs/suite-messaging.md` (the same rule; lines 39-40 rewritten), `docs/overview-and-scope.md` (one paragraph: apps stand
  alone and a Tenant System connects them), `docs/survey.md:59` (the `Declares` row no longer promises a future consumer)
- Wording only: no exchange, queue, code or test of Billing or Survey changes

## Tasks

1. **Permissions.** Every code is tenant scope. crm: `crm:customer:read`, `crm:customer:manage`, `crm:customer:export`,
   `crm:customer:erase`, `crm:field:manage` (custom-field and consent-topic definitions), `crm:order:read`,
   `crm:order:record`, `crm:activity:read`, `crm:activity:record`, `crm:webhook:manage` (endpoints and deliveries, worded
   as `survey:webhook:manage`). loyalty: `loyalty:program:read`, `loyalty:program:manage` (program, tiers, promotions; not
   grantable to a Tenant System because it changes what points are worth), `loyalty:member:read`, `loyalty:member:manage`
   (enrol), `loyalty:member:erase`, `loyalty:order:record` (orders and returns), `loyalty:points:redeem` (spend and
   reversal), `loyalty:reward:read`, `loyalty:reward:manage`, `loyalty:reward:redeem`, `loyalty:voucher:read`,
   `loyalty:voucher:use`, `loyalty:webhook:manage` (endpoints and deliveries, worded as `survey:webhook:manage`). All others
   are grantable.
2. **crm OpenAPI.** Base `/crm/api/v1`. Customers: `PUT /customers/{externalRef}` (upsert, `ETag`), `PATCH
   /customers/{externalRef}` (`If-Match`, `addTags`, `removeTags`), `GET /customers/{externalRef}`, `POST /customers/batch`,
   `GET /customers` (search and filter, cursor pagination), `GET /customers/{externalRef}/profile`, `GET /customers/export`
   (CSV stream), `POST /customers/{externalRef}/erasure`. Definitions: `GET|POST /custom-fields`, `PATCH
   /custom-fields/{key}`, `GET /tags`, `GET|POST /consent-topics`, `PATCH /consent-topics/{key}`. Consent: `POST
   /customers/{externalRef}/consents`, `GET /customers/{externalRef}/consents`. Orders: `PUT /orders/{orderRef}`, `GET
   /orders/{orderRef}`, `POST /orders/batch`, `PUT /orders/{orderRef}/returns/{returnRef}`, `GET
   /customers/{externalRef}/orders`. Activities: `POST /activities`, `POST /activities/batch`, `GET
   /customers/{externalRef}/activities`. Webhooks: `GET|POST /webhook-endpoints`, `GET|PUT|DELETE /webhook-endpoints/{id}`,
   `POST /webhook-endpoints/{id}/rotate-secret`, `GET /webhook-deliveries`, `POST /webhook-deliveries/{id}/retry`, as Survey's.
   Every operation has `x-required-permission`, error codes, and the batch caps from the plan's Key decisions.
3. **loyalty OpenAPI.** Base `/loyalty/api/v1`. Program: `GET|PUT /program` (currency, `currencyUnit` in minor units per point
   base, `expiryMonths`, `expiryNoticeDays`, `tierWindowMonths`, `timeZone`), `GET|PUT /tiers` (whole ordered list), `GET|POST /promotions`,
   `GET|PATCH /promotions/{id}`. Members: `PUT /members/{externalRef}` (enrol ahead of the first order; `201` or `200`,
   no body beyond an optional `displayName`), `GET /members/{externalRef}` (points, debt, tier, next tier, points expiring in
   a requested number of days), `GET /members/{externalRef}/ledger`, `POST /members/{externalRef}/erasure`. Orders: `PUT
   /orders/{orderRef}` and `PUT /orders/{orderRef}/returns/{returnRef}`, `GET /orders/{orderRef}` (what the order earned and
   what its returns took back). The order and return bodies use the field names of crm's order and return (`externalRef`,
   `currency`, `occurredAt`, `storeCode`, `channel`, lines `{lineNo, sku, category, quantity, amount, earnEligible}` and
   return lines `{lineNo, quantity, amount}`), and loyalty requires only the fields it uses, so a Tenant's forwarder maps a
   crm webhook onto a loyalty call field for field. The two apps share this shape as a contract, never as code. Spend: `POST /members/{externalRef}/redemptions`
   (`Idempotency-Key` required), `GET /members/{externalRef}/redemptions/{id}`, `POST
   /members/{externalRef}/redemptions/{id}/reversal`. Rewards: `GET|POST /rewards`, `GET|PATCH /rewards/{id}`, `POST
   /members/{externalRef}/reward-redemptions` (`Idempotency-Key` required), `GET /members/{externalRef}/vouchers`. Vouchers:
   `GET /vouchers/{code}`, `POST /vouchers/{code}/use` (`useRef`). A voucher's reads include its `rewardId`. Webhooks:
   `GET|POST /webhook-endpoints`, `GET|PUT|DELETE /webhook-endpoints/{id}`, `POST /webhook-endpoints/{id}/rotate-secret`,
   `GET /webhook-deliveries`, `POST /webhook-deliveries/{id}/retry`, as Survey's and crm's.
4. **Webhook payload schemas.** These four schemas describe the `data` of crm's signed webhooks to Tenant Systems; no AMQP
   exchange carries them. `data` shapes: `customer.created` `{customerId, externalRef, createdAt}`; `order.recorded` and
   `order.refunded` the same full order state `{customerId, externalRef, orderRef, orderVersion, currency, total,
   occurredAt, storeCode, channel, lines:[{lineNo, sku, category, quantity, amount, earnEligible, returnedQuantity,
   returnedAmount}]}`, and `order.refunded` also carries the return that caused it, `return: {returnRef, occurredAt,
   lines:[{lineNo, quantity, amount}]}`, so a forwarder can call loyalty's return endpoint without reading crm;
   `customer.erased` `{customerId}`. `aggregate` is `customer/<id>` or `order/<orderRef>`. State that `orderVersion` is
   monotonic per order, so a receiver that keeps the order's state can drop an older delivery.
   loyalty's twelve schemas follow the plan's "loyalty webhooks" decision: `member.enrolled` `{memberId, externalRef,
   enrolledAt, via ORDER|API}`; `points.earned` `{memberId, externalRef, orderRef, points, debtRepaid, available, debt}`;
   `points.clawed-back` `{memberId, externalRef, orderRef, returnRef, points, debtAdded, available, debt}`;
   `points.redeemed` and `points.redemption-reversed` `{memberId, externalRef, redemptionId, points, reference, available,
   debt}`; `points.expiring` `{memberId, externalRef, points, lots, earliestExpiresAt, noticeDays, available}` (`points` is
   the remaining points of the newly announced lots at notice time); `points.expired` `{memberId, externalRef, points, lots,
   available, debt}`; `tier.changed` `{memberId,
   externalRef, fromTier, toTier, direction UP|DOWN, qualifyingSpend}`; `voucher.issued` `{voucherId, code, rewardId,
   redemptionId, memberId, externalRef, expiresAt}`; `voucher.used` the same plus `useRef, usedAt`; `voucher.voided` the
   same plus `reason REVERSAL|ERASURE` (an `ERASURE` void has no `externalRef`); `member.erased` `{memberId}`. `aggregate` is `member/<memberId>`.
5. **Earn vectors.** A JSON schema and vectors, each `{program, tiers, promotions, steps:[{call, body, expectedStatus,
   expectedPoints, expectedLedgerDelta}]}`, where `call` is loyalty's own `order` or `return` request: base rate; HALF_EVEN
   rounding at the end; ineligible lines; best-promotion-wins; promotion window and time zone; day-of-week; store filter;
   tier multiplier; partial return; full return; a return after a rule change (snapshot used); a return that exceeds what was
   granted (debt); a return sent before its order (`422 ORDER_UNKNOWN`, then accepted after the order). Every number is
   hand-computed and the working is in a `note` field.
6. **Docs.** `docs/crm.md` and `docs/loyalty.md` record the identity rule, the idempotency rules, the debt model, the
   redemption model and the rule that crm and loyalty never call or listen to each other: a Tenant that uses both sends
   each order to both, or forwards crm's `order.*` webhooks to loyalty itself. `docs/loyalty.md` also says that loyalty
   only issues and consumes vouchers: delivering the reward (a POS discount, a gift, a Billing coupon) is the Tenant's job,
   through its own mapping from reward id, typically in its handler of the `voucher.issued` webhook. `docs/loyalty.md` lists
   the twelve webhook types and what a Tenant usually does with each. They say "planned" until phase 16.
7. **Suite rule.** Write the rule from the Context into `contracts/messaging/README.md`, `docs/suite-messaging.md` and
   `docs/overview-and-scope.md`, and fix the wording in `docs/survey.md:59`, as listed under Files to modify.

## Verification

- Every YAML and JSON file parses (`python3 -c 'import yaml,sys; yaml.safe_load(open(sys.argv[1]))'`, `python3 -m json.tool`).
- Validate the OpenAPI and event schemas with whatever the repo already uses: find it with
  `git grep -il "openapi" -- '*.gradle.kts' .github ops` and run the same check; if the repo has none, check each schema against
  its meta-schema with a one-off script kept out of the tree.
- Each vector's expected value is recomputed by hand once more by a second reader of the file.
- `git status` shows only the files listed above; `git diff -- docs/survey.md docs/suite-messaging.md
  contracts/messaging/README.md` changes wording only.
- `git grep -n -i "future app can react\|so a future app" -- docs contracts` finds nothing.

## Risks

- The OpenAPI files become stale as phases add detail. Later phases own their app's file and must update it in the same
  change as the endpoint; the skeleton's contract test (`OpenApiContractTest`) fails on drift.
- Vector numbers are easy to get subtly wrong; this is the one place a wrong constant would be copied into the engine.
