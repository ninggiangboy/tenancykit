# Loyalty

The suite's app for running a **Tenant's points program**: a lot-based points ledger, redemption, tiers, point expiry, a
rewards and voucher catalog, and promotions that multiply points by category, SKU, store, time or tier. It takes orders
and returns from the Tenant's own system through its own API, and sends signed webhooks of every change to a member's
points, tier and vouchers so the Tenant can react. It is a separate deployable (`loyalty/`) with the suite's three
runtimes: `migrate`, `api` and `worker`.

**Status: planned.** `loyalty/` is not built yet. This document is the contract the app is written against — what it
owns, the earn, lot, debt and expiry rules every later part measures against, and what a Tenant does with it. It is
revisited as the app ships.

loyalty keeps one program per Tenant, sends the member no message itself, and fulfils no reward. It keeps a replica of
Platform and reads nothing from another app. It never calls, listens to or names crm.

## What it is, and what it is not

| It is | It is not |
|---|---|
| The owner of a Tenant's program settings, tier definitions, promotion rules, members, points ledger and the rewards and vouchers a member can get | A sender: it messages no one; the Tenant tells the member from the webhooks |
| A producer of twelve signed webhooks to a Tenant System | A publisher of an AMQP exchange: there is no loyalty exchange, and no other app binds to a loyalty fact |
| The owner of orders and returns only as far as earn and returns read them, per member | A customer hub: it stores no profile curio beyond what points need, and it links to nothing |
| A REST API a Tenant System drives directly, one order and one return at a time | A batch importer: there is no backfill of historic orders; a Tenant starts at zero the day it is enabled |

The Tenant's data is the Tenant's: another Tenant's token cannot read or write it, and no other app reads it.

## Vocabulary

| Term | Meaning |
|---|---|
| Program | One per Tenant: a `currency`, a `currencyUnit` (minor units per point base), `expiryMonths`, `expiryNoticeDays`, `tierWindowMonths` and a `timeZone` |
| Member | One of a Tenant's customers in loyalty's eyes: an `externalRef` the Tenant chose, plus its points, debt and tier. Identified by `(tenant, external_ref)` |
| `externalRef` | The Tenant's own customer key. Chosen by the Tenant's system, unique per Tenant, immutable, and the same value the Tenant uses in the other app if it uses both |
| Order | One purchase recorded once and never changed: `orderRef` and the fields earn and returns read. A change of what was bought arrives as a return |
| Return | One return against an order, its own idempotent call: `returnRef` and lines with a quantity and an amount |
| Earn record | What one order earned, with the **snapshot** of the rules used: the program rate, the matched promotions and the tier multiplier |
| Lot | One grant of points: `(earned, remaining, expires_at)`. Points live in lots, never in one running total |
| Ledger | The append-only record of every change to a member's points: earn, clawback, redeem, reverse, expire |
| Balance | The sum of `remaining` over lots that have not expired, minus `debt`. Computed on read, never stored |
| Debt | What is owed when a clawback takes back more than the lots could give. Held on the member row; the next earn repays it first |
| Clawback | The points a return takes back, by the rules that granted them |
| Tier | An ordered band by `min_spend` with an `earn_multiplier`. The lowest has `min_spend = 0` |
| Qualifying spend | The eligible net amount of orders in the last `tierWindowMonths` (default 12) |
| Promotion | A rule that multiplies points by category, SKU, store, time or tier. The best matching one wins |
| Reward | A catalog entry a member can get for points: a points cost, an optional stock and a validity window |
| Voucher | A single-use code a reward redemption issues. The Tenant decides what it is worth |
| Redemption | One direct spend of points, with a required idempotency key: spend at once plus an idempotent reversal |

## Who owns what

**loyalty**
- Owns the program settings, the tier definitions and the promotion rules; the members, the orders and returns it stores,
  the lots, the ledger, the balance and the debt; redemption and its reversal; expiry and its notice; the rewards,
  vouchers and their redemption.
- Owns its webhook endpoints, its signed deliveries and its twelve-type event catalog.
- **Enforces** `loyalty:*` permissions from its replica of Platform data; it never mints, edits or decides them. The
  program and reward *definitions* are not grantable to a Tenant System, because changing them changes what points are
  worth; the rest of the codes are, since a Tenant System is the caller that drives the app.

**Platform**
- Owns tenants, domains, users, access, entitlement and the suite's tokens. loyalty keeps a replica of the five tables
  it enforces and reads no other Platform state.

**The Tenant System**
- Owns the point of sale and the web systems that send orders and returns, the `externalRef` values that name a member,
  and a member's `displayName` if it sends one.
- **Owns the reward.** loyalty issues a voucher; the Tenant maps the voucher's `rewardId` to what it actually grants — a
  POS discount, a gift, or a Billing coupon — and delivers it. loyalty never fulfils a reward.
- Owns every message to the member, sent from its handler of loyalty's webhooks.
- Owns the connection between loyalty and crm if it uses both (see *The suite rule*).

**Division rules**
- The tenant comes from the verified host, never from client input.
- Money is an integer in minor units with an ISO 4217 currency.
- loyalty never reaches into another app; another app never reaches into loyalty.

## Identity and enrolment

- A member is `(tenant, external_ref)`. The ref is the Tenant's own key: chosen by the Tenant's system, unique per
  Tenant and immutable. The Tenant uses the same ref in both apps if it uses both.
- loyalty generates its own UUID v7 surrogate id for the member and never stores another app's id.
- A member is enrolled by `PUT /members/{externalRef}` (`201` on create, `200` on replay, no body beyond an optional
  `displayName`), or by its **first order** — a member unknown to loyalty is created by the order that names it.
- An erased member's `external_ref` becomes null, so the same ref can later start a new member.
- `GET /members/{externalRef}` answers the member's points, debt, tier, next tier and the points expiring in a requested
  number of days.

## Orders and returns

- `PUT /orders/{orderRef}` records an order once and never changes it. `PUT /orders/{orderRef}/returns/{returnRef}`
  records a return as its own idempotent call.
- Both are idempotent by their natural key: the same `orderRef` or `returnRef` with the same body grants or takes back
  once and answers the stored result (`200`); with a different body it answers `409`.
- There is no batch order endpoint and no backfill. A Tenant starts with the orders it sends from the day loyalty is
  enabled; orders from before then never earn points.
- A return for an order loyalty has not seen is `422 ORDER_UNKNOWN`. The caller retries and succeeds once the order
  exists; nothing is held for later. A forwarder gets this for free from crm's webhook retry.
- An order before the program exists is `409 PROGRAM_NOT_CONFIGURED`; a currency other than the program's is `422
  CURRENCY_MISMATCH`.
- loyalty stores only the fields earn and returns read. Its order and return bodies use crm's field names —
  `externalRef`, `currency`, `occurredAt`, `storeCode`, `channel`, lines `{lineNo, sku, category, quantity, amount,
  earnEligible}` and return lines `{lineNo, quantity, amount}` — so a Tenant's forwarder maps a crm webhook onto a
  loyalty call field for field. loyalty does not read a line's `name` or the order's `total`.
- A return's quantity and amount never exceed what the order's own lines hold (`422`).
- `GET /orders/{orderRef}` answers what the order earned and what its returns took back.

## Earn: the formula and the snapshot

Points for an order are a pure function of the order and the rules in force at that moment:

```
points = floor( sum over eligible lines of (line amount × line multiplier) ÷ currencyUnit )
```

- `line multiplier` = the best matching promotion for the line (default 1) × the tier multiplier.
- The sum runs over `earnEligible` lines only, net of any returns. Every intermediate value is a decimal with at most 2
  fraction digits, and rounding is `HALF_EVEN` applied **once, at the end**.
- The order's **earn record stores the snapshot**: the program rate, the matched promotions and the tier multiplier that
  were used. A return recomputes with that stored snapshot, never with today's rules, so changing a promotion after the
  fact cannot move points already granted.
- The ledger receives the **delta** between what the order should now be worth and what was granted. A replayed order or
  return changes nothing.

The vectors in [contracts/loyalty/earn-vectors/](../contracts/loyalty/earn-vectors/) are the working of every number: the
base rate, rounding, ineligible lines, best-promotion-wins, windows and time zone, day-of-week, store filters, tier
multipliers, partial and full returns, a return after a rule change and a return that exceeds what was granted.

## Lots, ledger, balance and debt

- The ledger is append-only. Points live in **lots** `(earned, remaining, expires_at)`, not in one running total.
- The available balance is the sum of `remaining` over lots that have not expired, minus `debt`. It is computed on read
  and never stored.
- The member row holds the `debt` and is the **first lock of every flow**, as the wallet is in Billing. Every change to a
  member's points takes that lock before it reads or writes anything.
- Redemption consumes lots by earliest `expires_at`.
- A clawback consumes the returned order's own lot first, then lots by expiry. A shortfall becomes `debt`, and the next
  earn repays debt before it creates a lot. Redemption answers `409` while debt exists.

## Redemption and reversal

- Redemption is direct spend plus an idempotent reversal, not hold-and-commit. `POST
  /members/{externalRef}/redemptions` with a required `Idempotency-Key` spends at once; the same key yields one spend.
- `POST /members/{externalRef}/redemptions/{id}/reversal` restores the lots and voids a voucher that was unused. A POS
  that aborts a sale calls the reversal.
- Under concurrency the member row locks first, the idempotency check runs after the lock and lots update by ascending
  id, so parallel redemptions can never exceed the balance.

## Tiers

- Tiers are an ordered list by `min_spend`, each with an `earn_multiplier`; the lowest has `min_spend = 0`.
- Qualifying spend is the eligible net amount of orders in the last `tierWindowMonths` (default 12).
- A tier **rises in the same transaction** as the earn that crosses its threshold, and **falls only in the daily
  evaluation**, found through a `tier_review_at` timestamp. A member therefore never flaps up and down at a window
  boundary within a day: the drop waits for the daily run.

## Expiry and the notice job

- Each lot expires `expiryMonths` after it was earned (default 12). An expired lot is excluded from the balance and from
  spend at once.
- A scheduled job (hourly by default) writes one `EXPIRE` entry per due lot, with the member lock first, so an expiry
  transaction never races a redemption into an overspend.
- The program's `expiryNoticeDays` (default 30; `0` turns it off) drives a daily notice job that announces each lot
  once, as `points.expiring`, when the lot comes within that many days of expiring. The Tenant decides whether and how
  to tell the member.

## Rewards and vouchers

- A reward has a points cost, an optional stock and a validity window. Redeeming it spends points and issues a
  single-use voucher code in one transaction. Reward stock is decremented conditionally inside the member transaction,
  after the member lock, so it is never oversold.
- `POST /vouchers/{code}/use` is atomic and replayable by `useRef`; a voucher is used **at most once**, even under a
  hundred parallel uses.
- loyalty only **issues and consumes** vouchers. It never fulfils a reward. Delivering the reward is the Tenant's job,
  through its own mapping from the voucher's `rewardId` to what it grants — a POS discount, a gift, or a Billing coupon
  — typically in its handler of the `voucher.issued` webhook.
- A voucher's reads (`GET /vouchers/{code}`, `GET /members/{externalRef}/vouchers`) include its `rewardId`, so the Tenant
  always knows what the code stands for.

## Webhooks

loyalty sends **twelve** signed webhooks to the Tenant's subscribed endpoints. Each is written to the outbox in the
transaction of the change and delivered in process by the webhook context; there is no loyalty exchange, so a loyalty
fact leaves only as a webhook to the Tenant. A change that rolls back sends nothing.

Every payload carries `memberId` and `externalRef` — except `member.erased`, which carries `memberId` only — the amount
of the change and the member's available **balance** and **debt** after it. `aggregate` is `member/<memberId>`.
Deliveries may arrive out of order, so the balance in a payload is a point-in-time value and `GET /members/{externalRef}`
is the current one. An endpoint subscribes only to the types it needs.

| Type | Payload (`data`) | What a Tenant usually does with it |
|---|---|---|
| `member.enrolled` | `{memberId, externalRef, enrolledAt, via ORDER\|API}` | Links the member to its own customer record and starts a welcome flow. `via` says whether the first order or the member API enrolled it |
| `points.earned` | `{memberId, externalRef, orderRef, points, debtRepaid, available, debt}` | Shows or tells the member the points earned on a purchase; feeds a receipt or a loyalty app. `debtRepaid` says how much went to an old debt instead of the balance |
| `points.clawed-back` | `{memberId, externalRef, orderRef, returnRef, points, debtAdded, available, debt}` | Tells the member a return took points back and, if `debtAdded` is not zero, that the balance is in debt |
| `points.redeemed` | `{memberId, externalRef, redemptionId, points, reference, available, debt}` | Confirms the spend at the POS or in the app and records the `reference` the Tenant sent |
| `points.redemption-reversed` | `{memberId, externalRef, redemptionId, points, reference, available, debt}` | Restores what the member saw when a sale was aborted |
| `points.expiring` | `{memberId, externalRef, points, lots, earliestExpiresAt, noticeDays, available}` | Warns the member before points go. `points` is the remaining points of the newly announced lots at notice time; the message is the Tenant's |
| `points.expired` | `{memberId, externalRef, points, lots, available, debt}` | Tells the member what was lost. One event per member per expiry transaction, summing its lots |
| `tier.changed` | `{memberId, externalRef, fromTier, toTier, direction UP\|DOWN, qualifyingSpend}` | Congratulates or requalifies the member, and changes the tier's benefits in its own systems |
| `voucher.issued` | `{voucherId, code, rewardId, redemptionId, memberId, externalRef, expiresAt}` | **Delivers the reward**: maps `rewardId` to a POS discount, a gift or a Billing coupon, and hands the code to the member |
| `voucher.used` | the same, plus `useRef, usedAt` | Marks the reward delivered or spent in its own systems and reconciles the discount applied |
| `voucher.voided` | the same, plus `reason REVERSAL\|ERASURE` | Voids the reward in its own systems. An `ERASURE` void has no `externalRef` |
| `member.erased` | `{memberId}` | Drops its own copy of the member where it keeps one, and knows the points and vouchers are gone |

## Erasure

`POST /members/{externalRef}/erasure` is idempotent. It anonymizes the member, **voids its unused vouchers** and keeps
the ledger as anonymous totals keyed by the opaque member id. The member's `external_ref` becomes null, and loyalty sends
`member.erased` plus a `voucher.voided` with reason `ERASURE` for each unused voucher it voided.

Each app erases on its own endpoint, and erasing a person in loyalty does not erase them in another app. **A Tenant that
erases in one app must also erase in the other** if it uses both — or forward the erasure webhook: an `ERASURE` void and
a `customer.erased` meet the same duty from the other side. Erasing only in one app leaves the person in the other.

## The suite rule

loyalty never calls, listens to or knows crm. Nothing loyalty does needs crm, and no crm fact reaches loyalty. This keeps
loyalty optional: a Tenant can pair it with its own systems, another vendor's, or nothing at all.

A Tenant that uses both apps connects them itself. It either sends each order and return to both apps — both are
idempotent by the same `orderRef` and `returnRef` — or registers an endpoint for crm's webhooks and forwards
`order.recorded`, `order.refunded` and `customer.erased` to loyalty. Because loyalty's request bodies use crm's field
names, the forwarder is a field-for-field mapping, and both routes give the same points for the same order. This is the
Tenant's integration, not a loyalty feature.

## Non-goals

- More than one program per Tenant; more than one currency per program.
- A loyalty AMQP exchange, and any other app binding to a loyalty fact. A loyalty fact leaves only as a signed webhook.
- loyalty sending the member any message itself. The Tenant does it from the webhooks.
- Any link between loyalty and crm: no call, no event, no shared table and no shared code.
- Backfill or batch import of historic orders into loyalty.
- Point transfer between members; gift cards; coupons. Billing's coupon scope is a different feature that discounts
  Billing charges, not a POS-side reward.
- loyalty fulfilling a reward. It issues and consumes vouchers; the Tenant maps a `rewardId` to what it grants.
- Any UI or Console page; the app is API only.

## Documents this app owns

| Document | Owns |
|---|---|
| [contracts/permissions/loyalty.yaml](../contracts/permissions/loyalty.yaml) | The app's permission catalog: every `loyalty:*` code, tenant scope, most grantable to a Tenant System |
| [contracts/openapi/loyalty.yaml](../contracts/openapi/loyalty.yaml) | The REST surface |
| [contracts/loyalty/earn-vectors/](../contracts/loyalty/earn-vectors/) | The vector schema and the hand-computed vectors the earn engine must reproduce |
| [contracts/events/loyalty/](../contracts/events/loyalty/) | The twelve webhook payload schemas, one per type |
