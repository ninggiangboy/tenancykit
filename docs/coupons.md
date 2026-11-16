# Coupons

A Tenant gives a customer a price reduction or free credit with a **coupon**: a percent-off or amount-off discount, or a
one-time wallet credit, defined once and redeemed onto a wallet. A discount is applied in exactly one place — the charge
path every charge already passes through — so how prices are rounded, how the ledger is written and what an invoice
means do not change.

A coupon is not an eligibility or segmentation rule, a tax feature or a campaign tool. It carries no cohort, no
"new customers only" condition and no A/B assignment, and it measures nothing about who redeemed it. Discounting is a
Billing fact; deciding who receives the discount is the Tenant's job, and that decision reaches Billing when the coupon
is defined or when a code is redeemed.

## Vocabulary

| Term | Meaning |
|---|---|
| Coupon | The discount a Tenant defines: one benefit, one duration and one scope, with immutable terms |
| Promotion code | A code that redeems one coupon, with its own limits and validity |
| Redemption | One coupon redeemed onto one wallet, with the duration it still has left. One ACTIVE redemption per coupon per wallet |
| Benefit | What the coupon does: percent off, fixed amount off, or a wallet credit granted once |
| Duration | How long a redemption runs: any mix of a time window, an application cap and a total-discount cap |
| Scope | The charges a coupon applies to: by charge source, plan and metric |
| Application | One charge a redemption's discount is applied to; a redemption's application cap counts them |

## Benefits

A coupon has one benefit, and there are three kinds.

| Benefit | Effect | Grant |
|---|---|---|
| Percent off | The charge is reduced by the coupon's **basis points**, 1 to 10,000 (100%), rounded to the wallet's minor units | Applied to each eligible charge |
| Fixed amount off | The charge is reduced by a fixed amount in the wallet's currency | Applied to each eligible charge |
| Wallet credit | The wallet is credited once, at redeem, through the ordinary wallet credit path | Delivered at grant; the redemption ends at once |

A **free period** is a percent-off coupon at 10,000 basis points with an application cap of N: N eligible charges are
fully discounted, then the redemption is exhausted.

A credit coupon is redeemed inside the one transaction that creates its redemption: the wallet is locked, the
redemption is created and immediately ENDED (its benefit is delivered, nothing is left to apply), and the wallet is
credited with a key derived from the redemption, so a retry credits once. The ledger entry's reference is traceable to
the redemption.

## Duration and the total bound

A duration is any mix of three limits, and each is independent:

- A **time window from redeem**, given in days or in months and measured in UTC. It sets the redemption's `endsAt`.
- A **maximum number of applications**: after this many eligible charges the redemption is used up.
- A **maximum total discount** across the redemption's whole life, in the wallet's currency.

A limit that is not declared is unlimited, and a coupon with no declared limit runs **forever**. Because an unbounded
coupon is money given away with no stop, every coupon must declare at least one of `redeemBy`, `maxRedemptions` or
`budget` — the coupon's own expiry or total bound, separate from any single redemption's duration. A coupon that
declares none is refused at creation.

## Scope

A coupon filters the charges it may discount by:

| Filter | Matches |
|---|---|
| Charge source | The source of a charge (usage, subscription renewal, reservation capture, standalone) |
| Plan | The plan a charge was priced under |
| Metric | The metric a priced line measured |

An empty filter means **any charge of the wallet**. The filters combine, so a coupon scoped to one plan and one metric
applies only to charges that match both.

**Plan- and metric-scoped coupons apply only to charges whose caller supplies the plan and the metrics.** The two
callers that do are usage rating and subscription renewal, which price under a plan and carry their priced lines. A
reservation capture and a standalone charge carry no plan and no metric, so a plan or metric filter never matches
there; a source filter still does. No new request fields are added to make those charges carry a plan they do not
have.

### A metric-scoped coupon's base

A metric-scoped coupon discounts only what its own metrics cost. The base it is computed on is the smaller of the
running net and what is still undiscounted of the coupon's metrics, and applying it reduces those subtotals pro rata,
floor division with the remainder given to the first metric alphabetically. Two coupons scoped to the same metric
therefore cannot discount it by more than it cost.

When pricing's minimum or maximum charge replaced the calculated amount, the per-metric subtotals are scaled down to
fit the gross and never up: the gross is the ceiling a discount can reach. A line with no metric, such as a base or a
start fee, belongs to no metric and is discounted only by an unscoped coupon.

## Eligibility

A coupon applies to a charge only when every condition holds:

- The redemption is ACTIVE and not revoked, and now is before its `endsAt`.
- It has applications left and total-discount cap left.
- Its scope matches the charge.
- The gross amount is at least the coupon's minimum charge, when one is declared.
- The coupon is active.

A redemption that fails any of these is skipped, not applied partially.

## Ordering and stacking

Several coupons may run on one wallet, and the order they take is fixed so the same charges always produce the same
net.

| Rule | Decision |
|---|---|
| Kind order | Percent off first, then fixed amount off |
| Within a kind | The oldest redemption first, then the redemption id |
| Base | Each discount is computed on the **running net**: the amount left after the discounts already applied |
| Non-stackable | A non-stackable coupon runs alone, in its own candidate |
| Candidate selection | The stackable set applied together, and each non-stackable coupon applied alone, are the candidates; the candidate with the lowest net wins, and a tie goes to the candidate whose earliest redemption is oldest |

The stackable set is whatever stackable coupons are eligible for this charge, in the order above. The engine does not
stop at the first non-stackable coupon it meets; it evaluates the stackable set and every non-stackable coupon as
separate candidates and keeps the cheapest result, which is the one the wallet pays.

## Rounding and caps

A discount is computed once per coupon and then capped:

1. **One rounding.** A percent discount is `base × basisPoints ÷ 10,000`, rounded `HALF_UP` to whole minor units in the
   wallet's currency. A fixed amount is already whole minor units.
2. **Caps.** The discount is the smallest of the computed value, the coupon's per-application maximum, the redemption's
   remaining total-discount cap, the coupon's remaining budget and the running net.
3. **Floor.** The discount is never below 0, so a charge never becomes a credit.

A coupon-wide budget applies partially when less remains than the discount would take, so a budgeted coupon never
over-spends: the last charge it discounts takes only what the budget has left. Only a coupon that carries a budget
makes one coupon row hot across wallets; a coupon bounded by an application cap alone does not.

## Worked example

A gross of 100,000 charged to a wallet holding `SAVE20` (20% off) and `WELCOME10K` (10,000 off):

| Step | Running net |
|---|---|
| Gross | 100,000 |
| `SAVE20` (percent, 20%) | 80,000 |
| `WELCOME10K` (fixed amount) | **70,000** |

The Charge records gross 100,000, a discount of 20,000 and a discount of 10,000, and the wallet pays 70,000 — the
ledger debit. Were the order reversed, the fixed amount would take 10,000 (leaving 90,000) and the percent would take
20% of what remained (18,000), leaving **72,000**. Both are the same two coupons; only the fixed order decides which
number is charged.

## Redemption lifecycle

A redemption has one of three states:

| State | Meaning |
|---|---|
| `ACTIVE` | Running: it discounts the eligible charges it meets while the duration lasts |
| `ENDED` | Its duration ran out and it discounts nothing more |
| `REVOKED` | An administrator ended it early; it discounts nothing more |

A redemption ends — becoming `ENDED` — when it reaches its application cap, when it reaches its total-discount cap, or
when it passes its end time. An administrator ends one early by revoking it, which makes it `REVOKED`. Deactivating the
coupon ends every running redemption only when `revokeRedemptions` is set, and those end as `COUPON_DEACTIVATED`; by
default a deactivated coupon stops new redemptions and leaves the running ones working, so a charge already in flight
is still explained by the coupon it used.

Reaching the end time **writes nothing**. No row is updated and no event is published for a redemption that simply
runs out of time; its state is derived from `endsAt` when the next charge reads it. The one exception is exhaustion
by a cap, which a charge observes as it applies the last application or the last of the budget.

## Where the discount sits

A discount is applied after the charge is final but before it is recorded and collected. In order:

1. The plan prices the charge, applying its **single rounding** and then its **minimum and maximum charge**; the
   result is the **gross**.
2. The charge transaction locks the wallet, reads its idempotency key, then calls `claim` on the discount port exactly
   once. `claim` returns a quote — the coupons, their order and each amount — that is guaranteed to be consumable.
3. The wallet is charged the **net**. `consume` runs only when the charge succeeded, updating counters, budgets and
   redemption state. The Charge, its discount lines, the ledger entry and the event commit in the one transaction.

A failed charge **consumes nothing**: the wallet could not pay it, so no application, no budget and no redemption state
is spent, and no discount lines are recorded. A replay consumes nothing either: the recorded Charge is read before
`claim`, so a repeated request returns the original result and does not discount a second time. See
[wallet-ledger-and-charging.md](wallet-ledger-and-charging.md) for the charge path and
[concurrency-and-locking.md](concurrency-and-locking.md) for the locks `claim` takes.

## The fully discounted charge

When the discounts bring the net to zero, the charge is still recorded — as a **succeeded Charge of 0** — so that the
application it consumed and a later replay both have a record to point at. A zero-net charge writes **no ledger entry
and moves no wallet balance**, because there is nothing to post, and it produces **no invoice line**, because the
invoice reflects what the wallet paid. A balance of entries stays positive, as it always was.

Two consequences follow, both validated:

- A **zero capture** — a reservation whose charge was fully discounted — closes the Reservation and returns the whole
  hold, so no budget is held against a charge that cost nothing.
- Requesting an invoice for a zero-net charge on demand is refused with **409**, because there is nothing to invoice.

## Estimate

An estimate may name a wallet. With a wallet it returns the **net** the wallet would pay and the discount lines that
produced it, alongside the gross, through the same **read-only preview** the charge uses: the preview takes no locks
and writes nothing, and it does not consume an application or a budget.

An estimate without a wallet is the gross alone, unchanged. A Reservation made from an estimate holds the **gross**,
not the discounted net: the discount is applied when the reservation is captured, when the coupon state at that moment
is known. See [pricing-and-usage.md](pricing-and-usage.md).

## Permissions

| Act | Permission |
|---|---|
| List and read coupons, promotion codes and redemptions | `billing:coupon:read` |
| Create and deactivate a coupon or a promotion code | `billing:coupon:manage` |
| Attach a coupon to a wallet by its id | `billing:coupon:manage` |
| Revoke a redemption | `billing:coupon:manage` |
| Redeem a promotion code onto a wallet | `billing:coupon:redeem` |

Redeeming a code needs only `billing:coupon:redeem`, so a Tenant System can run a promotion without being able to
bypass a code's limits. Attaching a coupon by its id and revoking a redemption need `billing:coupon:manage`, because
both bypass or override the limits a code would impose. All three codes are grantable to a Tenant System, so a Tenant
System may define, list, read and deactivate coupons and promotion codes and redeem them through the API, for the
customers mapped to it only; a wallet that is not mapped to the caller answers 404. The creating actor is stored and
audited. The codes and their scopes are in
[contracts/permissions/billing.yaml](../contracts/permissions/billing.yaml).

## Events

| Event | Sent when |
|---|---|
| `coupon.redeemed` | A coupon was redeemed onto a wallet; it carries the redemption's limits and `endsAt` |
| `coupon.redemption-ended` | A redemption ended, with the reason `EXHAUSTED`, `REVOKED` or `COUPON_DEACTIVATED` |

Three existing events gain additive fields, so a consumer that ignores them keeps working:

| Event | Added |
|---|---|
| `charge.succeeded` | Optional `grossAmount` and `discounts` |
| `charge.failed` | Optional `grossAmount` and `discounts` |
| `subscription.renewed` | `paidAmount` |

Every Billing event is subscribable as a webhook, because the webhook catalog is the schema folder, so a Tenant learns
the whole life of a redemption without polling. The event list and each payload shape are owned by the schemas in
[contracts/events](../contracts/events/README.md); see [webhooks-and-events.md](webhooks-and-events.md).

## Lock order

| Flow | Locks in order |
|---|---|
| Redeem a coupon | wallet, then the coupon row, then the promotion code row, then the new redemption |
| Charge with coupons | wallet, then the coupon rows that carry a budget, then the redemption rows, ascending by id |

A redemption belongs to its wallet, so the wallet is locked first in both flows. Only a coupon that carries a budget is
a row shared across wallets, so only those coupon rows are locked during a charge; a coupon bounded by an application
cap alone touches no shared coupon row. Every flow locks the wallet before any coupon or redemption row, and **no flow
locks a wallet after a coupon row**, so no wait cycle can form with the redeem flow. `claim` and `consume` run in the
same transaction, so a budget read by `claim` cannot change before `consume` spends it. The full order and its rules
are in [concurrency-and-locking.md](concurrency-and-locking.md).

## Granting a coupon from another system

A coupon can originate outside Billing — a loyalty programme that issues a voucher, a CRM campaign that grants a
discount. **Suite apps never link to each other**: with no Billing change, this is the Tenant System's integration,
which holds the mapping between the other system's reward and a Billing coupon id or code. Billing only ever sees that
id or code.

The loyalty voucher, end to end:

1. Loyalty publishes `voucher.issued`. The Tenant System handles it; Billing subscribes to nothing from loyalty and is
   not involved in issuing the voucher.
2. The Tenant System maps the voucher's `rewardId` to a coupon id or a promotion code it keeps in its own store.
3. It grants the coupon in Billing with `Idempotency-Key: loyalty-voucher:<voucherId>`, so a retry of the same voucher
   grants once.
4. It marks the voucher used, holding the redemption as the voucher's `useRef`. The grant is the last step, so a
   voucher is marked used only after Billing has answered.
5. Billing's `coupon.redeemed` webhook tells it when the benefit starts — a credit coupon is already applied, a percent
   or amount coupon applies to the next eligible charge — and carries the redemption's limits and `endsAt`.
   `coupon.redemption-ended` tells it when the benefit ends.
6. When Billing refuses for good — every code invalid, the coupon deactivated, the total bound reached — the Tenant
   System reverses the loyalty reward redemption it had made for the voucher.
7. Before the member spends points, the Tenant System checks that the coupon is active and that the wallet holds no
   ACTIVE redemption of it, so a second voucher for the same member is not granted into a coupon the wallet already
   holds.

There are two grant routes, and the choice is a matter of who selects the coupon:

| Route | Needs | Code limits | Pick it when |
|---|---|---|---|
| Attach by `couponId` | `billing:coupon:manage` | none; the coupon is attached directly | The Tenant System decides the coupon itself and no customer-facing code is involved |
| Redeem a code | `billing:coupon:redeem` | the code's own limits and validity apply | A code is the reward, or the Tenant System holds one code per campaign and lets Billing enforce its limits |

Attach by id is the route for a Tenant System that maps a reward to a coupon; redeem a code is the route when the code
itself is what the member was given, or when its per-code limits are wanted. Give the code no per-customer limit when
one customer may receive the same reward more than once. A coupon's total bound and its `redeemBy` should be sized to
cover the reward's stock and the voucher's validity, so Billing's bound and the programme's limit agree.

## Settled behaviour

| Question | Answer |
|---|---|
| Does a reservation made from an estimate hold the gross or the discounted amount? | The **gross**; the discount is applied when the reservation is captured |
| What is a fully discounted charge? | A succeeded **Charge of 0** with no ledger entry and no wallet movement |
| Do plan- and metric-scoped coupons apply to reservation capture and standalone charges? | **No**; those callers supply no plan or metric, so those filters never match |
| What does deactivating a coupon do to running redemptions? | They **keep working** unless `revokeRedemptions` is set, which ends them as `COUPON_DEACTIVATED` |

## Non-goals

- Eligibility and segmentation rules: new customers only, cohorts, A/B assignment.
- Any UI, including coupon entry in the Customer Portal.
- Tax.
- Restoring usage when a Charge is reversed.
- Campaign analytics.
- Stripe-side coupons or Stripe Checkout discounts.
- Cross-currency coupons.
- Discount lines as first-class invoice lines; the invoice shows the net charge.
- Editing a coupon's terms after creation.
- Auto-generated bulk code batches beyond one code per request.

## Related

The charge path the discount sits in: [wallet-ledger-and-charging.md](wallet-ledger-and-charging.md). Estimating with a
wallet: [pricing-and-usage.md](pricing-and-usage.md). Net invoice lines and renewals:
[invoicing-and-subscriptions.md](invoicing-and-subscriptions.md). The new and extended events:
[webhooks-and-events.md](webhooks-and-events.md). The lock order: [concurrency-and-locking.md](concurrency-and-locking.md).
