---
title: "CRM and loyalty apps — a retail Tenant's customer data hub and its points program"
description: "Two API-only deployables delivered together that never link to each other: crm/ keeps a retail Tenant's customers, orders with line items, activities, custom fields, tags and consent and sends signed webhooks to the Tenant; loyalty/ takes orders from the Tenant's own system, runs points, tiers, expiry, rewards and promotions, and sends signed webhooks of every points, tier and voucher change. The Tenant System is the only integrator."
status: in-progress
priority: P1
effort: "16 phases, 8 × M, 8 × L"
branch: dev
tags: [feature, backend, api, database, infra]
blockedBy: []
blocks: []
created: 2026-11-16
---

# CRM and Loyalty Apps

## Overview

A retail Tenant (supermarket-style) runs its own point-of-sale and web systems outside the suite. Nothing in the suite keeps
who those customers are or what they bought, and nothing rewards them. This plan adds two API-only deployables:

- `crm/` is the customer data hub. The Tenant's own system sends customers, orders with line items, returns and activities
  by API or batch; crm stores them, serves a 360 profile with search, filters and CSV export, and sends signed webhooks of
  what changed to the Tenant's endpoints.
- `loyalty/` runs the Tenant's points program: a lot-based points ledger, redemption, tiers, point expiry, a rewards and
  voucher catalog, and promotions that multiply points by category, SKU, store, time or tier. It takes orders and returns
  from the Tenant's own system through its own API, and sends signed webhooks of every change to a member's points, tier
  and vouchers, so the Tenant can react (notify the member, grant a reward in another system such as a Billing coupon).

**Suite rule (2026-11-16).** Suite apps never link to each other. Neither app calls, listens to or knows the other; a Tenant
that uses both connects them itself, by sending each order to both or by forwarding crm's webhooks to loyalty. This keeps
each app optional and lets a Tenant pair either one with its own systems or another vendor's. Phase 1 writes the rule into
the shared contracts and docs; the existing apps already obey it in code and change only in wording.

Scope was fixed in the `/ak-advise` interview of 2026-11-16 (memory: `crm-loyalty-app-decisions`) and revised the same day
by a second interview that introduced the suite rule. Preview of the first interview:
`plans/reports/advise-261116-0318-crm-loyalty-preview.html` (its crm→loyalty arrow is superseded). Four policy decisions
were taken while planning (see the Validation Log). The user's heavier choices (all four loyalty extras in v1, line items
kept, two apps in one delivery) are kept; their cost is in the risk register.

## Outcome contract

**Goals**

1. A Tenant's system creates and updates customers, records orders with lines and returns, and writes activities, one at a
   time or in batches, and reads a customer back as one 360 profile.
2. A Tenant's POS shows a member's points, tier and expiring points, redeems points and rewards, and uses a voucher,
   through one API, with no double spend under concurrency.
3. A Tenant's system records orders and returns in loyalty, directly or by forwarding crm's webhooks, and a return takes the
   points back by the same rules that granted them; both routes give the same points for the same order.
4. Two new apps join the suite with no Java change in Platform, Billing, mailroom, survey or attribution; each runs, tests and
   serves its API without the other, and the existing apps behave identically whether or not either is deployed.
5. The suite rule "apps never consume each other's business events; the Tenant System connects them" is written in the
   shared contracts and docs, and the broker permissions of the new apps enforce it.
6. Every fact a Tenant may act on leaves the app that owns it as a signed webhook: crm's customer and order changes, and
   loyalty's enrolment, earn, clawback, redemption, reversal, expiry notice, expiry, tier, voucher and erasure changes.

**Done means**

- `crm/` and `loyalty/` build, migrate and test green; ArchUnit and Modulith gates pass in both.
- `git diff` of the whole delivery shows no change under `platform/**/*.java`, `billing/`, `mailroom/`, `survey/`,
  `attribution/`, `mailer/`, `notification/`, `tenant/`, `core/` or `core/core-suite/**/SuiteActorResolver.java`. Platform
  changes only `application.yml` (two service-client entries) and picks up the two permission files at build time.
- `git grep -n -i crm -- loyalty/apps loyalty/contexts loyalty/libs` and `git grep -n -i loyalty -- crm/apps crm/contexts
  crm/libs` find nothing; `git grep -n "crm.events"` finds nothing outside `plans/`; neither app declares an exchange.
- `contracts/openapi/{crm,loyalty}.yaml`, `contracts/permissions/{crm,loyalty}.yaml`,
  `contracts/events/crm/*.schema.json` and `contracts/events/loyalty/*.schema.json` (the two apps' webhook payloads) exist,
  and contract tests prove the running apps match them.
- `contracts/messaging/README.md`, `docs/suite-messaging.md` and `docs/overview-and-scope.md` state the suite rule, and
  `git grep -n -i "future app can react" -- docs contracts` finds nothing.
- Tests prove, in crm: the same `orderRef` sent twice with the same body records one order and with a different body
  answers `409`; the same `returnRef` sent twice records one return; a batch of 1,000 customers reports each item's outcome;
  tenant A cannot read or write tenant B's data on any table or endpoint; an erasure request clears every personal field of
  the customer, its activities and its consent contact data and sends `customer.erased` to the Tenant's endpoints; every
  webhook verifies with the endpoint's secret and matches its schema; no endpoint or webhook of crm needs loyalty.
- Tests prove, in loyalty: 100 parallel redemptions never exceed the balance; the same `Idempotency-Key` yields one spend;
  the same `orderRef` or `returnRef` with the same body grants or takes back once and with a different body answers `409`;
  every vector in `contracts/loyalty/earn-vectors/` passes through the real endpoints, including returns, promotions and
  tier multipliers; a return before its order is `422` and succeeds once the order exists; a return after the points were
  spent leaves a debt, the balance is negative, redemption answers `409` until it is positive, and the next earn repays the
  debt first; expiry takes lots in expiry order and never touches a lot that was already spent; a tier rises on the earn
  that crosses its threshold and falls only in the daily evaluation; a voucher is used at most once under 100 parallel uses;
  the first order of an unknown ref enrols the member; the erasure endpoint anonymizes the member and voids its unused
  vouchers; each of the twelve loyalty webhook types is delivered once per subscribed endpoint, signed, and matches its
  schema, and a change that rolls back sends nothing.
- `ops/smoke/crm-loyalty.sh` on the compose stack plays the Tenant System on two paths: sending each order and return to
  both apps, and forwarding crm's webhooks to loyalty through a smoke-only forwarder; both give the same points for the
  same order, the forwarded order earns within 5 seconds, and with either app stopped the other one and Billing, mailroom,
  survey and attribution behave as before.
- `docs/crm.md` and `docs/loyalty.md` describe what exists and how a Tenant connects the two; `ops/smoke/isolation.sh`
  proves neither new broker user can publish to any exchange or bind to another app's exchange.

**Non-goals**

Case, complaint or ticket handling; analytics, RFM and saved segments; sales pipeline, leads and deals; crm consuming events
from Billing, mailroom, survey or attribution (those apps may post to the activity API later, with an adapter this plan does
not build); automatic merging of customers by phone or email; any UI or Console page; more than one loyalty program per
Tenant; more than one currency per loyalty program; a loyalty AMQP exchange (its facts leave only as webhooks); loyalty sending the member any message itself (the Tenant does it from the webhooks); any link between crm and
loyalty (call, event, shared table or shared code); a `crm.events` exchange; removing `billing.events` or `survey.events`
or any code change in an existing app; integrating crm or loyalty into the sample Tenant System (a later piece of work);
backfill or batch import of historic orders into loyalty; point transfer between members; gift cards; coupons (Billing's
coupon scope is a different feature: it discounts Billing charges).

**Constraints**

Tenant always from the host, never from client input; `external_ref` is chosen by the Tenant's system, unique per Tenant and
immutable, and the Tenant uses the same ref in both apps if it uses both; crm and loyalty never call, listen to or name
each other, and share a contract shape for orders, never code; each app keeps the order lines it needs (crm all fields,
loyalty only what earn and returns read); money is an integer in minor units with an ISO 4217 currency;
existing suite patterns (jOOQ with a committed generated schema, RLS, suite tokens, ETag and `If-Match`, `core/` shared
modules, api/worker/migrate runtimes, outbox and inbox); conventional commits without AI references; no plan or phase number
in code, migration or test names.

## Evidence from the scout (verified in source on 2026-11-16)

| Finding | Source | Consequence |
|---|---|---|
| Platform's `application.yml` lists service clients `billing` and `notification` only; compose passes `PLATFORM_MAILROOM_SERVICE_CLIENT_*` to Platform but no list entry reads them | `platform/apps/app-boot/src/main/resources/application.yml:67-74`, `docker-compose.yml:253-256`, `git grep -i mailroom -- platform` is empty | Phase 2 must verify on a running stack whether mailroom's replica client can authenticate before copying its wiring, and adds explicit `crm` and `loyalty` entries |
| A new app's out-of-tree touchpoints are known | `git grep -l -i mailroom -- . ':!mailroom' ':!plans' ':!docs' ':!attribution' ':!survey'` | The list in phases 2 and 16 comes from this grep, re-run at execution time |
| Survey and attribution are in flight: their working trees have uncommitted changes and neither has ops files yet | `git status`, `git grep -l -i attribution -- . ':!attribution' ':!plans' ':!docs'` | Shared ops files are edited append-only and re-grepped when touched; this plan never edits `survey/` or `attribution/` |
| Suite tokens carry one audience and only that app's scopes; a Tenant System token has `tenant_id` | `contracts/tokens/README.md` | crm and loyalty are driven by a Tenant System credential with `crm:*` and `loyalty:*` codes |
| A permission code is `<app>:<resource>:<action>` with `scopes` and `grantable_to_tenant_system`; the app a Tenant's own system drives makes almost every code grantable | `contracts/permissions/mailroom.yaml:1-45` | Both files follow it; `settings:manage` style codes stay not grantable |
| Mailroom's API is served under `/mailroom/api/v1/...` and Caddy strips the prefix | `contracts/openapi/mailroom.yaml:16`, `ops/caddy/Caddyfile` | `/crm/api/v1/...` and `/loyalty/api/v1/...` |
| Platform data replicates by state-transfer events with a monotonic `version`; consumers drop older versions | `contracts/messaging/README.md` (Replicated data) | crm's order webhooks carry the order's full state and an `orderVersion`, so a Tenant that keeps order state can drop an older delivery by the same rule |
| The envelope's `id` is the deduplication key; an event without a schema in `contracts/events/<producer>/` is never published | `contracts/messaging/README.md` (Envelope) | Four schemas under `contracts/events/crm/` describe crm's webhook payloads, packaged by the build like `mailroom/apps/app-boot/build.gradle.kts:69` |
| A consumer declares queue bindings per exchange; binding needs `read` on the source exchange | `mailroom/.../config/MessagingConfiguration.java:20-60`, `core/core-messaging/.../topology/SuiteTopology.java`, `ops/rabbitmq/declare-users.sh:8-11` | crm and loyalty each declare only `<app>.platform-events`; their broker users get `read` on `platform.events` alone, which is what enforces the suite rule |
| No app consumes another app's business exchange: `billing.events` is consumed only by Billing itself, `survey.events` by nobody; mailroom and notification use `mailer.*`, the suite's delivery infrastructure, which has no permission catalog | `git grep -E 'billing\.events\|survey\.events'` (only `billing/`, `core/` tests, `ops/smoke/isolation.sh`, `survey/` config), `billing/.../PostpaidCycleIntegrationTest.java:168` (`billing.events.malformed`, consumer `InvoicingConsumers.CYCLE`), `ls contracts/permissions` | The suite rule needs no code change in existing apps; only the wording "so a future app can react" (`docs/suite-messaging.md:39-40`, `docs/survey.md:59`, `contracts/messaging/README.md:79`) contradicts it |
| Survey's outbox feeds its webhooks in process: `OutboxRelay` publishes a Spring `EventEnvelope`, `WebhookEventListener` records deliveries in the relay's transaction; `BrokerRelay` is a separate bean | `core/core-messaging/.../outbox/OutboxRelay.java:117`, `survey/contexts/webhook/.../WebhookEventListener.java`, `survey/apps/app-boot/.../config/{WebhookConfiguration,OutboxConfiguration}.java` | crm copies `survey/contexts/webhook` (committed, 43 files) and wires `OutboxRelay` only; no `BrokerRelay`, no exports, no exchange |
| `core-webhook` can allow loopback and private destinations for development | `core/core-webhook/.../WebhookHttpSender.java:47` (`allowNonPublicTargets`) | The smoke forwarder receives crm's webhooks in compose; the setting is `false` everywhere else |
| The sample Tenant System integrates Billing only | `git grep -l -i -E 'mailroom\|survey\|attribution' -- tenant` is empty | The end-to-end proof is a smoke script playing the Tenant, as for the other apps; the sample tenant is a later piece of work |
| `RepeatableCreate`, `Fingerprint`, `ConditionalUpdate`, `ExpectedVersion` and `IdempotencyConflictException` already exist | `core/core-kernel/src/main/java/dev/ngb/core/{idempotency,version,error}` | Order and return idempotency and ETag updates reuse them; no new core code |
| Billing locks the wallet first and checks idempotency after that lock; jobs use leases and run records in their own short transactions | `docs/concurrency-and-locking.md:7-20, 38-60` | Loyalty locks the member row first and checks the idempotency key after it; its jobs use the copied lease tables |
| Billing's `customer.created` has no email and maps customers to Tenant System users by `externalUserId` | `contracts/events/billing/customer.created.schema.json` | Confirms the decision that crm does not match customers to Billing; the Tenant system owns identity |
| Mailroom's skeleton is committed and final: `apps/app-boot`, `contexts/*`, `libs/{shared-core,jooq-schema}`, `testing/arch-fixtures`, `build-logic` | `ls mailroom` | Copy it; do not copy from the in-flight attribution or survey trees |
| Docs exist per app and the root docs count deployables in words | `ls docs`, `docs/overview-and-scope.md` | Phase 16 counts what exists with `grep -i`, not what plans promise |
| Platform's list of apps (and so the valid entitlement `app_code` values) is the permission catalogs under `contracts/permissions`, packaged at build time | `platform/apps/app-boot/build.gradle.kts:51-56`, `platform/apps/app-boot/.../catalog/ClasspathAppCatalog.java`, `.../tenancy/.../port/AppCatalog.java` | No Platform Java change; Platform must be rebuilt after phase 1 before `crm` or `loyalty` can be enabled for a Tenant |

## Key decisions

- **Two builds, one delivery, no link.** `crm/` and `loyalty/` are separate Gradle builds with their own databases and roles;
  they share nothing but `core/` and Platform's `platform.events`. Neither calls, listens to or names the other. The supported
  parallelism is crm versus loyalty, and after the skeletons the two chains are fully independent; inside one app run phases
  in order.
- **The Tenant connects them.** A Tenant that uses both apps either sends each order, return and erasure to both (both are
  idempotent by the same `orderRef`, `returnRef` and `externalRef`), or registers its own endpoint for crm's webhooks and
  forwards `order.recorded`, `order.refunded` and `customer.erased` to loyalty. loyalty's request bodies use crm's field names,
  so the forwarder is a field-for-field mapping. A Tenant that uses one app sees nothing of the other.
- **crm contexts.** `platform` (replica), `customers` (customers, bulk upsert, search and filter, custom fields, tags, consent,
  erasure), `orders` (orders, lines, returns), `activity` (activities), `profile` (360 view, export; reaches the other
  contexts only through their `api` packages and writes nothing), `webhook` (endpoints and signed deliveries, copied from
  Survey; it only listens to outbox events).
- **loyalty contexts.** `platform` (replica), `program` (program settings, tier definitions, promotion rules), `points`
  (members, orders and returns, lots, ledger, earn, redemption, expiry, erasure), `tiers` (evaluation over the spend each
  order's earn record holds), `rewards` (catalog, vouchers), `webhook` (endpoints and signed deliveries, copied from Survey
  as in crm; it only listens to outbox events). `points` reaches `program` only through its `api`; `tiers` and
  `rewards` reach `points` only through its `api`; `points` reaches `tiers` and `rewards` only through ports it declares,
  wired in app-boot, so the graph has no cycle.
- **Entitlement.** Each app's entitlement filter refuses a Tenant without that app on every endpoint, as in mailroom. Nothing
  filters events, because neither app receives another app's events.
- **Identity.** A customer in crm and a member in loyalty are each `(tenant, external_ref)` with the ref chosen by the
  Tenant's system; each app generates its own UUID v7 surrogate id and never stores the other's. crm never matches by phone
  or email; phone and email are stored attributes with a plain index for search. An erased customer's `external_ref`
  becomes `erased:<id>` in crm and null in loyalty, so the same ref can later start a new customer or member. A member is
  enrolled by `PUT /members/{externalRef}` or by its first order.
- **Ingest.** crm's single and batch endpoints and loyalty's single order and return endpoints are idempotent by natural key:
  customers and members by `external_ref`, orders by `orderRef`, returns by `returnRef`, activities by an optional
  `activityRef`. loyalty has no batch order endpoint (no backfill). A repeated key with the same body replays the stored
  result (`200`); with a different body it answers `409`. A batch reports each item (`CREATED`, `UPDATED`, `UNCHANGED`,
  `REPLAYED`, `FAILED` with a code) and never fails as a whole because one item is bad. Defaults: 1,000 customers or
  activities per batch, 100 orders per batch with at most 5,000 lines together, 500 lines per order, activity payload up to
  8 KiB; all are settings.
- **Order shape.** An order carries `orderRef`, `externalRef`, `currency`, `occurredAt`, `storeCode`, `channel`, `total` and
  lines `{sku, name, category, quantity (3 decimals), amount (net of discount), earnEligible (default true)}`. Customers
  must exist (`422 CUSTOMER_UNKNOWN`), unless the request sets `createCustomer: true`, which upserts a customer with that
  `externalRef` and no other data. A return names the order and lines with quantity and amount; returned quantity and amount
  never exceed what was sold (`422`). loyalty takes the same shape (without `name` and `total`, which it does not read) and
  applies the same return rule against the lines it stored.
- **crm webhooks.** crm sends `customer.created`, `order.recorded`, `order.refunded` and `customer.erased` as signed webhooks
  to the Tenant's subscribed endpoints, written to the outbox in the same transaction as the change and turned into
  deliveries in process by the copied webhook context. There is no `crm.events` exchange. The two order events both carry
  the order's **complete current state** (lines with cumulative returned quantity and amount) and an `orderVersion` that
  rises with every return; `order.refunded` also carries the return that caused it, so a forwarder needs no other call.
- **Orders in loyalty.** An order is recorded once and never changed; each return is its own idempotent call. A return for
  an order loyalty has not seen is `422 ORDER_UNKNOWN` and the caller retries, which a forwarder gets for free from crm's
  webhook retry. An order before the program exists is `409 PROGRAM_NOT_CONFIGURED`; a currency other than the program's is
  `422 CURRENCY_MISMATCH`. Nothing is held for later.
- **loyalty webhooks.** Twelve types, each written to the outbox in the transaction of the change and delivered in process
  by the copied webhook context, as in crm: `member.enrolled`, `points.earned`, `points.clawed-back`, `points.redeemed`, `points.redemption-reversed`, `points.expiring`, `points.expired`, `tier.changed`, `voucher.issued`, `voucher.used`, `voucher.voided`, `member.erased`. Every payload carries `memberId` and `externalRef` (except
  `member.erased`, which carries `memberId` only), the amount of the change and the member's available balance and debt
  after it. Deliveries may arrive out of order; the balance in a payload is a point-in-time value and `GET
  /members/{externalRef}` is the current one. `points.expired` is one event per member per expiry transaction, summing its
  lots. `points.expiring` is the advance notice: one event per member per notice run, for the lots that entered the
  program's notice window since the last run, each lot announced once. `tier.changed` carries the old and new tier and
  `UP` or `DOWN`. The voucher events carry `voucherId`, `code`,
  `rewardId` and `redemptionId`; `voucher.voided` carries `REVERSAL` or `ERASURE`. There is no loyalty exchange.
- **Earn is a pure function of a snapshot.** loyalty computes `points = roundHALF_EVEN(sum(line amount × line multiplier) ÷
  currencyUnit)` over eligible lines net of returns, where the line multiplier is the best matching promotion (default 1)
  times the tier multiplier, all decimals with at most 2 fraction digits, rounded HALF_EVEN at the end only. It stores the
  rules it used (program rate, matched promotions, tier multiplier) on the order's earn record. A return recomputes with that
  stored snapshot, never with today's rules, and the ledger receives the **delta** between what the order should now be worth
  and what was granted. A replayed order or return changes nothing.
- **Ledger.** Append-only entries; points live in **lots** `(earned, remaining, expires_at)`; the available balance is the sum
  of `remaining` over lots that have not expired, minus `debt`, computed on read and never stored. A member row holds the
  `debt` and is the first lock of every flow, as the wallet is in Billing.
  Redemption consumes lots by earliest `expires_at`. A clawback consumes the returned order's own lot first, then by expiry;
  a shortfall becomes `debt`; the next earn repays debt before it creates a lot. Redemption answers `409` while debt exists.
- **Redemption.** Direct spend plus an idempotent reversal, not hold-and-commit: `POST …/redemptions` with a required
  `Idempotency-Key` spends at once, `POST …/redemptions/{id}/reversal` restores the lots (and voids a voucher that was
  unused). A POS that aborts a sale calls the reversal.
- **Tiers.** Ordered tiers by `min_spend` with an `earn_multiplier`; the lowest has `min_spend = 0`. Qualifying spend is the
  eligible net amount of orders in the last `tier_window_months` (default 12). A tier rises in the same transaction as the
  earn that crosses its threshold and falls only in the daily evaluation, found through a `tier_review_at` timestamp.
- **Expiry.** Each lot expires `expiry_months` after it was earned (default 12). An expired lot is excluded from the balance
  and from spend at once; a scheduled job (hourly by default) writes one `EXPIRE` entry per due lot, member lock first.
  The program's `expiry_notice_days` (default 30, 0 turns it off) drives a daily notice job that announces each lot once,
  `points.expiring`, when it comes within that many days of expiring; the Tenant decides whether and how to tell the member.
- **Rewards and vouchers.** A reward has a points cost, an optional stock and validity window; redeeming it spends points and
  issues a single-use voucher code in one transaction. `POST /vouchers/{code}/use` is atomic and replayable by `useRef`.
  loyalty never fulfils a reward itself: the Tenant maps a voucher's `rewardId` to what it grants (a POS discount, a Billing
  coupon) in its own system.
- **Erasure.** Each app erases on its own endpoint. crm anonymizes the customer and sends `customer.erased` to the Tenant;
  loyalty, on `POST /members/{externalRef}/erasure`, anonymizes the member, voids unused vouchers and keeps the ledger as
  anonymous totals keyed by the opaque member id. Erasing in one app does not erase in the other unless the Tenant calls
  both or forwards the webhook; both docs say so.

## Extension trace

The credible next variants are another Tenant-side consumer of crm's facts (a Tenant's marketing tool reacting to orders)
and another system posting activities. A new consumer is a new webhook endpoint of the Tenant and changes no crm code,
because the payloads carry full state and the schemas are versioned in `contracts/events/crm/`; phase 9 proves it with two
endpoints. A new activity source posts a new `type` string to the existing activity API, which phase 8 proves with a
second, unrelated type. A third suite app that wants every order (a recommendation app) would be fed by the Tenant the same
way; if that fan-out ever becomes the Tenant's main burden, a suite-owned bus is a deliberate change of the suite rule, not
something this plan prepares. loyalty's earn function takes one order snapshot and returns ledger deltas; a second earn
source (a birthday bonus) would add a ledger entry kind and a caller, not a new boundary, and nothing is built for it now.
No registry, plugin system or rule-language is built.

## Cross-plan coordination

- **Survey** (`261116-0145-survey-app`) and **attribution** (`261116-0304-attribution-app`) are in flight with uncommitted
  work in the tree when this plan was written. This plan edits nothing under `survey/` or `attribution/`.
- **Shared ops files** (`docker-compose.yml`, `.env.example`, `ops/**`, `.github/workflows/*`, `docs/overview-and-scope.md`,
  README files) are edited by several plans. Edit them append-only, re-run the `git grep` in phases 2 and 16 when you start,
  and resolve conflicts by keeping both sides.
- **Billing coupons** (`coupon-decisions`) discount Billing charges. loyalty vouchers are a separate, POS-side reward.

## Phases

| # | Phase | Size | Depends on |
|---|---|---|---|
| 1 | [Scope, docs, contracts and decisions](phase-01-scope-docs-contracts-and-decisions.md) | M | — |
| 2 | [Identity and broker seams for both apps](phase-02-identity-and-broker-seams.md) | M | 1 |
| 3 | [crm build skeleton, replica and gates](phase-03-crm-build-skeleton-and-gates.md) | L | 2 |
| 4 | [loyalty build skeleton, replica and gates](phase-04-loyalty-build-skeleton-and-gates.md) | L | 2 |
| 5 | [crm customers, batch upsert and lookup](phase-05-crm-customers-and-batch-upsert.md) | L | 3 |
| 6 | [crm custom fields, tags and consent](phase-06-crm-fields-tags-and-consent.md) | M | 5 |
| 7 | [crm orders, lines, returns and order events](phase-07-crm-orders-returns-and-events.md) | L | 5 |
| 8 | [crm activities, 360 profile, search and export](phase-08-crm-activities-profile-search-export.md) | M | 6, 7 |
| 9 | [crm erasure and the webhook contract proof](phase-09-crm-erasure-and-event-contract.md) | M | 8 |
| 10 | [loyalty program, tiers and promotion rules](phase-10-loyalty-program-tiers-and-promotions.md) | M | 4 |
| 11 | [loyalty earn engine, lots and the order/return API](phase-11-loyalty-earn-engine-and-ledger.md) | L | 10 |
| 12 | [loyalty balance, redemption, reversal and debt](phase-12-loyalty-redemption-and-debt.md) | L | 11 |
| 13 | [loyalty tier evaluation](phase-13-loyalty-tier-evaluation.md) | M | 11 |
| 14 | [loyalty point expiry](phase-14-loyalty-point-expiry.md) | M | 11 |
| 15 | [loyalty rewards and vouchers](phase-15-loyalty-rewards-and-vouchers.md) | L | 12 |
| 16 | [loyalty erasure, ops seams, end-to-end and docs](phase-16-erasure-ops-end-to-end-and-docs.md) | L | 9, 13, 14, 15 |

Phases 3 and 4 run in parallel (disjoint trees `crm/` and `loyalty/`). After them the crm chain (5-9) and the loyalty chain
(10-15) run in parallel; the two meet in phase 16. Phases 6 and 7 touch different contexts but share one migration directory
and one committed jOOQ schema: run them in order unless two sessions agree on the reserved migration ranges (each phase names
its own) and regenerate the schema after rebasing. Phases 13, 14 and 15 are likewise ordered unless coordinated. The loyalty
chain never waits for the crm chain: it is exercised through its own API from the start, and only phase 16's forwarding path
needs both.

## Risk register

| Risk | Likelihood | Impact | Mitigation | Phase |
|---|---|---|---|---|
| Mailroom's (and so every new app's) replica client is not registered in Platform | Medium | A new app's replica never loads in compose | Verify with a real token request first; add explicit entries for the new apps; report a mailroom finding, do not fix mailroom here | 2 |
| Two apps in one delivery double the skeleton, contract and ops work | Certain | Schedule | Phases end in something runnable; the skeleton is a copy; the apps share nothing but `core/` | all |
| An order with many lines makes a large outbox row and webhook body | Medium | Relay or delivery fails | 500-line cap as a setting, a test delivering the maximum order through the relay and webhook, the cap lowered if the test fails | 7, 9 |
| Orders and returns reach loyalty out of order or twice | High | Wrong points | Orders and returns idempotent by `orderRef`/`returnRef` with fingerprints; a return before its order is `422` and retried; vectors and a parallel-return test | 11 |
| A Tenant that dual-writes sees crm and loyalty disagree after one call fails | Medium | A customer's points miss an order | Same natural keys in both apps make the retry safe; `docs/loyalty.md` gives the dual-write and forwarding recipes; the smoke proves both paths give equal points | 11, 16 |
| A Tenant erases in crm and forgets loyalty (or the reverse) | Medium | Personal data left in one app | Each app's erasure is its own idempotent endpoint; the forwarding recipe includes `customer.erased`; both docs state the duty | 9, 16 |
| The compose-only `allowNonPublicTargets` leaks into a deployed environment | Low | crm can call internal addresses | Default `false`, set only in the compose profile, absent from `ops/terraform`, a default-settings test | 3, 16 |
| Copying Survey's webhook context carries Survey names and settings into crm or loyalty | Medium | Wrong headers or event types | Rename to `Crm-*` and `Loyalty-*`, point each catalog at its own `contracts/events/<app>/`, grep for `survey` in both trees | 3, 4 |
| A busy Tenant floods its endpoint with a webhook per earn | Medium | Slow or dead deliveries | An endpoint subscribes only to the types it needs; deliveries retry on the copied schedule and go `DEAD` with a resend endpoint; the relay batch is a setting | 4, 16 |
| A return after redemption makes the balance negative | Certain | Support questions | Debt model, redemption refused with `409`, next earn repays debt, shown in the balance response and docs | 12 |
| Concurrent redemptions or a redeem racing the expiry job overspend | Medium | Points created from nothing | Member row lock first, idempotency check after it, lots updated by ascending id; the 100-way test and an expiry race test | 12, 14 |
| Promotion or tier rules change after points were granted | Certain | A return recomputes with new rules | The snapshot on the earn record is the only input to a recompute; a test changes the rules between order and return | 11 |
| Tier flapping at a window boundary | Medium | Annoyed members | Rise at once, fall only in the daily job, one evaluation timestamp per member; boundary vectors | 13 |
| Daily jobs scan every member | Medium | Slow at scale | `tier_review_at` and `expires_at` indexes pick only due rows; per-member short transactions; a bounded batch | 13, 14 |
| A batch upsert half-fails | Medium | Partial data | Per-item transactions and per-item results; idempotent by natural key so a retry is safe | 5, 7 |
| Personal data left behind after erasure (activity payloads, consent contact data, logs) | Medium | Compliance | A schema-driven test lists every column that can hold personal data and asserts it is cleared; no personal data in logs | 9, 16 |
| A voucher is used twice | Medium | Money loss | One conditional update from `ISSUED` to `USED`; replay by `useRef`; the 100-way test | 15 |
| Reward stock oversold | Medium | Over-redemption | Conditional decrement inside the member transaction, after the member lock | 15 |
| Orders from before a Tenant started sending them to loyalty never earn points | Certain | A new loyalty Tenant starts at zero | Accepted: the project is new and has no historic data; no backfill or batch import is built; documented as a v1 limit | 16 |
| The erasure retry record, an outbox payload or a webhook delivery body keeps the person's POS id | Medium | Erasure is incomplete | `erasure_requests` stores a hash of the ref, not the ref, in both apps; relayed outbox rows, inbox rows and finished deliveries are purged by housekeeping within a setting; all are classified in the personal-data test | 9, 16 |
| Shared ops files conflict with survey or attribution work | High | Merge pain | Append-only edits, regrep at execution time, one phase (2, 16) owns them | 2, 16 |
| Existing apps change behaviour because crm or loyalty exists | Low | Violates the main goal | The diff gate in Done means; the suite-rule edits are wording only; the smoke test stops each new app and checks the others | 1, 16 |

## Open questions

- Phase 2 finding: whether mailroom's replica client authenticates today. The planning evidence is stale: Platform's
  `application.yml` now lists `mailroom` and `survey` service clients beside `billing` and `notification`, so no missing
  wiring is expected; the live token request in phase 2 confirms it.
- The 500-line and 1,000-item caps and the 8 KiB payload cap are defaults chosen at planning time, not measured; phase 7
  measures the order cap against the outbox, relay and webhook delivery.

## Validation Log

### Session 1 (2026-11-16)

| Question | Decision | Propagated to |
|---|---|---|
| A return after the points were spent | Allow a negative balance recorded as debt; block redemption until it is positive; the next earn repays debt first | plan (Key decisions), phases 11, 12 |
| How the member tier is computed | Qualifying spend in the last N months (default 12); rise at once, fall only in the daily evaluation | plan, phases 10, 13 |
| When points expire | Each lot `expiry_months` after it was earned (default 12), spent lot by lot in expiry order | plan, phases 10, 11, 14 |
| The earn base of an order | Per line `amount` (net of discount) and `earnEligible` (default true); the base is the sum over eligible lines net of returns | plan, phases 7, 11 |

### Session 2 (2026-11-16, second `/ak-advise`: the suite rule)

| Question | Decision | Propagated to |
|---|---|---|
| Should suite apps link to each other? | No. The Tenant System is the only integrator; loyalty takes orders through its own API, never from crm | plan (Overview, Outcome contract, Key decisions), phases 2, 4, 11, 16 |
| How does crm tell the outside what changed? | Signed webhooks to Tenant Systems through `core-webhook`, like Survey; no `crm.events` exchange | plan, phases 1, 2, 3, 5, 7, 9 |
| What changes in the existing apps? | Wording only: the rule goes into `contracts/messaging/README.md`, `docs/suite-messaging.md`, `docs/overview-and-scope.md`; the "future app can react" wording goes; `billing.events` (Billing consumes it itself) and `survey.events` stay | plan, phase 1 |
| How does a customer become a member? | Its first order enrols it; `PUT /members/{externalRef}` enrols ahead | plan, phases 1, 11 |
| Where is "the Tenant connects them" proven? | A smoke script playing the Tenant, with a smoke-only forwarder; the sample Tenant System is integrated later | plan, phase 16 |
| Backfill or batch import of orders into loyalty? | No: the project is new and has no historic data; lots still expire `expiry_months` after processing | plan (Non-goals, Risk register), phase 11 |

Removed by this session: the consumer entitlement gate, `loyalty.crm-events`, `held_orders` and `HeldOrderReleaseJob`,
`orderVersion` handling and the reordering test in loyalty, `customer.created` creating members,
`EventConsumerExtensionTest`, the constraint "line items live in crm only", and the open questions about loyalty without crm
and about backfill.

### Session 3 (2026-11-16, `/ak-cook`: execution decisions)

| Question | Decision | Propagated to |
|---|---|---|
| `floor` in the earn formula against `HALF_EVEN` in the Key decisions and the rounding vector | HALF_EVEN, applied once to the final integer; 2.5 becomes 2 and 3.5 becomes 4 | plan (Key decisions), phases 1, 11 |
| Does mailroom's replica client exist in Platform? | Yes: the list entry is present beside `survey`'s; the planning evidence predates that change | plan (Open questions), phase 2 |



### Decisions taken by the planner (not asked; reversible, flagged for the user)

- crm's webhook catalog is four types: `customer.created` is kept so a Tenant's other systems learn about a customer that
  has not bought yet.
- Both order webhooks carry the order's complete state and an `orderVersion`, and `order.refunded` carries the return that
  caused it, so a forwarder needs no call back to crm. The names `order.recorded` and `order.refunded` from the preview are
  kept.
- Orders and returns are idempotent by their natural keys (`orderRef`, `returnRef`), not by a header, in both apps.
- loyalty stores order lines with only the fields earn and returns read, and answers a return before its order with `422`
  instead of holding it.
- Redemption is spend plus reversal, not hold and commit.
- Consent topics are defined per Tenant like custom fields, not a fixed list of channels.
- A member's balance is computed from lots on read, not stored, and the tier uses the spend recorded on each order's earn
  record, so no separate spend table exists.
- Points expire on a lot's own date; the expiry job is hourly by default but never affects what the API reports.

### Verification Results

- Claims checked: 30 (Quick tier: `test -f`, `grep` and `sed -n` against the tree on 2026-11-16). Verified: 30. Failed: 0.
  Unverified: 3, listed below.
- Verified examples: all 20 source paths in the Evidence table exist; Platform's `application.yml` lists only `billing` and
  `notification` as service clients while compose passes `PLATFORM_MAILROOM_SERVICE_CLIENT_*`; Platform builds its app
  catalog from `contracts/permissions` (`build.gradle.kts:51-56`, `ClasspathAppCatalog`); `RepeatableCreate`, `Fingerprint`,
  `ConditionalUpdate`, `ExpectedVersion` and `IdempotencyConflictException` exist; `MessagingHousekeeping` exists in mailroom;
  survey and attribution have no ops files yet.
- Not verified at planning time (stated in the phases): that mailroom's replica client authenticates today (phase 2 tests
  it); that the copied outbox, relay and webhook delivery carry a 500-line order (phase 7's `OrderEventSizeTest` decides the
  cap); that `crm_owner` may create `pg_trgm` (phase 5 decides).
- Session 2 checked 7 more claims on 2026-11-16, all verified: `OutboxRelay` publishes in process (`OutboxRelay.java:117`)
  and Survey's `WebhookEventListener` records deliveries from it; `BrokerRelay` and the non-empty exports assertion are
  separate beans (`survey/.../OutboxConfiguration.java:30-60`); `survey/contexts/webhook` is committed (43 files);
  `WebhookHttpSender` takes `allowNonPublicTargets` (line 47); binding needs `read` on the source exchange
  (`declare-users.sh:8-11`); Billing consumes its own `billing.events` (`PostpaidCycleIntegrationTest.java:168`); the
  sample Tenant System integrates Billing only. `ops/smoke/isolation.sh` has no bind check today (only publish and queue
  reads), so phase 16 adds one.

## Red Team Review

Four lenses (assumptions, failure modes, security, scope) applied to the draft by the planner, each finding backed by a path.
No reviewer subagents were spawned.

| # | Severity | Finding | Evidence | Disposition |
|---|---|---|---|---|
| 1 | High | loyalty would consume `crm.events` for every Tenant, so Tenants using crm alone would fill `held_orders` and member tables | the consumer queue is per app, not per Tenant (`contracts/messaging/README.md`, Topology) | Superseded in session 2: loyalty consumes no crm event, so the gate and `held_orders` are gone |
| 2 | High | `erasure_requests` stored the POS's original `external_ref`, keeping the tie to a person that erasure exists to cut | `contracts/events/billing/customer.created.schema.json` shows the suite treats such ids as the link to a person | Accepted: store `sha256(tenant ‖ ref)` (phase 9) |
| 3 | Medium | Published outbox rows and inbox rows keep `externalRef` in payloads after erasure | `mailroom/.../jobs/MessagingHousekeeping.java` is the only retention template; its behaviour is unconfirmed | Accepted: retention setting and a test (phase 9), classified in the personal-data test |
| 4 | Medium | Orders from before loyalty was enabled never earn points; no replay exists | queues start empty when declared (`core-messaging` topology) | Superseded in session 2: no queue exists; the user ruled out backfill because the project has no historic data |
| 5 | Medium | Draft said the member row stores the balance while the design computes it from lots | internal inconsistency between plan Key decisions and phase 12 | Accepted: balance computed on read; plan and phases aligned |
| 6 | Low | `tiers` and `rewards` call `points` while `points` needs tier and voucher hooks, a possible cycle | the context gates in phases 3 and 4 forbid cycles | Accepted: ports declared in `points`, implemented in app-boot (plan, phases 11, 13, 15) |
| 7 | Low | The migration directory and jOOQ schema are shared by phases of one app | `contracts`-style reserved ranges are not used elsewhere in the repo | Accepted: reserved ranges per phase and an order-first rule (plan, phases 3 and 4) |
| 8 | Info | Several Tenant Systems of one Tenant (POS and web) share customers | `contracts/tokens/README.md` (token carries `tenant_id`, not an owner of customers) | Rejected as a defect: shared customers are intended; documented in phase 16 |

### Whole-Plan Consistency Sweep

All 16 phase files and `plan.md` were re-read after the edits. Checked: the dependency table against each phase's frontmatter
(all 16 match), phase links (all 16 resolve), migration ranges (`V10`-`V50` for crm in phase 3, `V10`-`V70` for loyalty in
phases 4 and 10-16), permission codes in phase 1 against their use in later phases, the event names and `schemaVersion`
across phases 1, 7, 9 and 11, and the stale terms "spend entries", "stored balance" and "daily job" (none remain; the two
remaining hits for "spend table" state that none exists). No unresolved contradiction.

Session 2 sweep (2026-11-16): `plan.md` and phases 1, 2, 3, 4, 5, 7, 9, 11 and 16 were edited; phases 6, 8, 10 and 12-15 had
no crm-event dependency and are unchanged. A grep for `crm.events`, `crm-events`, `held_orders`, `HeldOrder`,
`EventOrdering`, `EventConsumerExtension`, `orderVersion`, `customer.created`, `customer.erased`, `crm only`, `backfill` and
`customerId` over all files leaves only intended hits (crm's webhook payloads, the removal notes and the non-goals). Phase
titles and dependencies match the table.
