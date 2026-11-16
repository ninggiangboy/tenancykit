---
phase: 5
title: "Phase 5: Charge seam in money"
status: done
priority: P1
effort: "L"
dependencies: [3, 4]
---

# Phase 5: Charge seam in money

## Goal

Make `ChargeUseCase` apply discounts: add the money-owned `ChargeDiscounts` port, record gross and discount lines on a
Charge, charge the net, support a fully discounted charge, and implement the port in `coupon`. After this phase a
standalone charge and a reservation capture are discounted end to end.

## Context (verified in source)

- `ChargeUseCase.charge` locks the wallet, reads the idempotency key, calls `ChargeService.charge`, then
  `charges.add`, `ledger.append`, `wallets.update`, reservation update and the outbox event, in one transaction.
- `Charge`, `ChargeService`, `InvoiceItem`, `LedgerEntry` and `Reservation.capture` all require a positive amount;
  `charges.amount` has `check (amount > 0)` (`V6__money.sql`). `CollectChargeUseCase` and `InvoiceChargeUseCase` turn a
  charge into an invoice line.
- The replay fingerprint is `(wallet, reservation, amount, usageRef, reference)`. It stays as it is: the command's
  `amount` is the gross, and the discount is decided on the server.
- Migrations that alter a populated table avoid long locks: a constant default is catalog-only, and a new check is
  added `not valid` then validated (`V24__resource_versions.sql` explains the lock timeout the migrate profile uses).

## Design

- Port in `money.api` (`billing/contexts/money/src/api/java/dev/ngb/billing/money/api/charge/`):
  `ChargeDiscounts` with `preview`, `claim`, `consume`; value types `DiscountRequest(walletId, gross, ChargeContext, now)`,
  `DiscountQuote(gross, net, lines)`, `DiscountLine(redemptionId, couponId, couponName, benefit, amount)`, and
  `ChargeContext(kind, planId, metricAmounts)` with kinds `USAGE`, `SUBSCRIPTION_RENEWAL`, `RESERVATION`, `STANDALONE`.
  A `ChargeDiscounts.NONE` implementation keeps money's own tests free of the coupon module.
- `ChargeCommand` gains an optional `context` (null means the kind follows from the source). The context is not part of the
  replay fingerprint.
- `charges` gains `discount_total bigint not null default 0`; gross is `amount + discount_total`. `amount` may be `0` only
  when the status is SUCCEEDED and `discount_total > 0`. New table `charge_discounts` (owned by money): charge id, seq,
  redemption id, coupon id, coupon name, benefit, amount (`> 0`), unique on `(charge_id, seq)`, row-level security.
- Flow inside the existing transaction, after the wallet lock and the idempotency read: `claim` → net.
  - net positive: `ChargeService.charge(net)` as today; a failure records the Charge with the net as `amount`, gross
    recoverable, no discount lines, and does not call `consume`.
  - net zero with a positive gross: a succeeded Charge of 0, no ledger entry, no wallet post; a reservation capture of
    zero closes the reservation as CAPTURED with 0 and returns the whole hold (a separate method; `capture` keeps
    rejecting zero).
  - then `charges.add` with its lines, `consume`, and the outbox event.
- `coupon` implements the port in `coupon.application.discount.service`, mapping `ChargeContext` onto the domain's
  `ChargeFacts`. `claim` locks coupons that carry a budget and the wallet's ACTIVE redemptions in ascending id order and
  returns remaining budgets to `DiscountPlanner`; `consume` updates `applications_used`, `discount_total`, budget use and
  ENDED transitions with the repository's conditional statements, and writes `coupon.redemption-ended` with `EXHAUSTED`
  to the outbox for each redemption it ends, in the charge transaction. A failed or replayed charge writes none.
- Invoicing: `CollectChargeUseCase` ignores a zero-net charge; `InvoiceChargeUseCase` answers 409 for one.
- Events and responses: `charge.succeeded` and `charge.failed` gain optional `grossAmount` and `discounts`;
  `ChargeSucceededPayload` gains an optional `grossAmount`; the charge HTTP response and `ChargeView` gain `grossAmount`
  and `discounts`. All additive.

## Files

- Create: `V28__charge_discounts.sql` (`discount_total`, relaxed check added `not valid` then validated, `charge_discounts`)
- Regenerate and commit: `billing/libs/jooq-schema/**`
- Modify (money): `ChargeUseCase`, `Charge`, `ChargeService`, `Reservation`, `ChargeCommand`, `ChargeResult`, `ChargeView`,
  `ChargeController`, `JooqChargeRepository`, `JooqChargeReadRepository`, `MoneyEvents`, `ChargeSucceededPayload`,
  `MoneyConfiguration` (inject the port; default `NONE`)
- Modify (invoicing): `CollectChargeUseCase`, `InvoiceChargeUseCase` and their tests
- Create (coupon): `application/discount/service/CouponChargeDiscounts.java`, repository methods for claim and consume
- Modify: `CouponConfiguration` (expose the implementation as the port bean)
- Modify: `contracts/events/billing/charge.succeeded.schema.json`, `charge.failed.schema.json`; regenerate the OpenAPI files
- Modify: `ArchitectureRules.TABLE_OWNER` (`ChargeDiscounts` → `money`)

## Steps

1. Back up any non-test database, then write `V28__charge_discounts.sql` and regenerate jOOQ.
2. Add the port and value types to `money.api` and a `NONE` implementation; thread the context through `ChargeCommand`.
3. Extend `Charge` and `ChargeService` for gross, discount lines and the zero-net path; add the zero capture to
   `Reservation`. Unit-test each (positive, zero-net, failed, reservation, zero reservation).
4. Rework `ChargeUseCase`: claim, charge net, add, consume, publish. Keep the key read after the wallet lock.
5. Implement `CouponChargeDiscounts` and wire it; confirm that `money` still compiles without the `coupon` module on its
   classpath.
6. Invoicing guards for zero-net charges.
7. Update the payload parser, schemas, response and view; regenerate the OpenAPI contract.
8. Integration tests through `HttpWorld` for a standalone charge and a reservation capture with `SAVE20` + `WELCOME10K`:
   net 70,000, two discount lines, ledger debit 70,000, wallet balance, replay returns the same charge without a second
   consumption, an insufficient-funds charge consumes nothing and a later success does, 100% off gives a zero-net charge
   with no ledger entry and no unbilled line on a Postpaid wallet.

## Todo

- [x] Migration, regenerated jOOQ, `TABLE_OWNER` entry
- [x] Port, value types, `NONE`, `ChargeCommand` context
- [x] `Charge`, `ChargeService`, `Reservation` zero paths with tests
- [x] `ChargeUseCase` claim → charge → add → consume
- [x] `CouponChargeDiscounts` and wiring
- [x] Invoicing guards and tests
- [x] Event schemas, payload, response, view, regenerated contract
- [x] Integration tests listed above

## Verification

- `cd billing && ./gradlew :contexts:money:test :contexts:invoicing:test :contexts:coupon:test`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*Charge*' --tests '*Coupon*' --tests '*JooqSchemaTest' --tests '*OpenApiContractTest' --tests '*ArchitectureTest' --tests '*ModulithVerificationTest'`
- Confirm no `UPDATE` or `DELETE` path was added to the ledger repository (the existing ArchUnit rule covers it).

## Risks

- This is the highest-risk phase because it edits the one money path. Keep each step a separate commit, run the money
  and ledger tests after each, and never reorder the wallet lock and the idempotency read.
- `consume` failing after the ledger append would leave a charge without consumption: both run in the same transaction,
  so a failure rolls the charge back; test it with a forced failure.
- Replay of a charge whose coupon has since ended must return the recorded result and consume nothing.

## Security considerations

Discount lines are written only by the use case inside the tenant-scoped transaction; the new table has row-level
security. The client cannot supply a discount: `ChargeContext` is set by trusted in-process callers, and the HTTP layer
sets only the kind.
