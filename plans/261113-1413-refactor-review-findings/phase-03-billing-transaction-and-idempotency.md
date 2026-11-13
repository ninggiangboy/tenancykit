---
phase: 3
title: "Billing transaction and idempotency"
status: pending
priority: P1
effort: M
dependencies: [1]
---

# Phase 3: Billing transaction and idempotency

## Goal

Every Billing read that depends on the tenant scope runs inside the unit of work, every event payload is decoded by
one typed decoder with an explicit malformed-event policy, and the payment/refund state transitions are atomic.

## Findings addressed

- `payment/.../OpenPaymentMethodsUseCase.java:68`: `stripeCustomers.find(...)` runs outside the transaction;
  `stripe_customers` is under `force row level security`, so the read sees `app.tenant_id` unset and always returns
  empty.
- `invoicing/.../CollectChargeUseCase.java:90-105` and `ApplyPaymentUseCase:100-105` decode payloads with unchecked
  casts and swallow `NPE | ClassCastException | IllegalArgumentException` into `Optional.empty()`, silently dropping
  revenue-relevant events. The two decoders duplicate each other.
- `payment/domain/payment/Payment.java:134-145`: `succeed()` returns `APPLIED` without changing state; the transition
  only happens in `recordLedgerEntry`, so a caller can leave a payment `REQUIRES_ACTION`.
- `invoicing/.../CloseBillingCycleUseCase.java:59-61` swallows the failure cause and only increments a counter.
- `money/.../ChargeUseCase.java:79,90` reads the same reservation twice.
- `pricing/.../ChargeFromUsageUseCase.java:211-218` re-implements SHA-256 instead of `Fingerprint.sha256`.
- `PaymentSettlement.java:188,265` uses the literal `"stripe"` as ledger actor instead of an actor constant.
- `CreateSubscriptionUseCase.java:98-100` opens a second transaction only to reload the result and uses bare
  `orElseThrow()`.
- `RequestRefundUseCase`/`InvoiceChargeUseCase`/`ReceiveStripeWebhookUseCase` constructors carry 12–13 dependencies,
  which is why they have no focused tests.

## Files to create / modify

- Modify `billing/contexts/payment/.../payment/command/OpenPaymentMethodsUseCase.java`: perform the read inside the
  transaction; return the row from `addIfAbsent` or re-read inside the same lambda.
- Create `billing/contexts/money/src/api/java/dev/ngb/billing/money/api/event/ChargeSucceededPayload.java` (record +
  `parse(Map<String,Object>)`) and use it in `CollectChargeUseCase`.
- Create `billing/contexts/payment/src/api/java/dev/ngb/billing/payment/api/payment/PaymentSucceededPayload.java` and
  use it in `ApplyPaymentUseCase`; delete both private decoders.
- Create `billing/contexts/shared-kernel` (or `money/api/event`) `MalformedEventException`; the consumer logs at WARN,
  increments a counter and records the inbox entry, or throws to the DLQ per the documented policy. Choose one policy
  and test it.
- Modify `billing/contexts/payment/.../payment/Payment.java`: `succeed(entryId, now)` performs the transition and
  `recordLedgerEntry` disappears; update `PaymentSettlement`.
- Modify `billing/contexts/invoicing/.../CloseBillingCycleUseCase.java`: log the cause with wallet/period at WARN.
- Modify `billing/contexts/money/.../ChargeUseCase.java`: `walletOf` returns `record LedgerTarget(WalletId, Reservation)`
  (or the reservation is loaded once after the lock) so the reservation is read once.
- Modify `billing/contexts/pricing/.../ChargeFromUsageUseCase.java`: use `Fingerprint.sha256`.
- Create `billing/contexts/payment/src/application/.../messaging/service/PaymentActors.java` with
  `STRIPE = "system/stripe"`; replace the two literals (document the persisted value migration).
- Modify `billing/contexts/invoicing/.../CreateSubscriptionUseCase.java`: `renewIfDue` returns the updated
  subscription; remove the reload transaction.
- Modify `billing/contexts/payment/.../RequestRefundUseCase.java`, `.../InvoiceChargeUseCase.java`,
  `.../ReceiveStripeWebhookUseCase.java`: extract `RefundGateway`/`AuditPreamble` collaborators (full constructor
  split can follow in phase 10, but the gateway extraction is needed for tests here).
- Create tests: `ChargeUseCaseTest`, `CollectChargeUseCaseTest` (malformed payload), `PaymentSettlementTest`,
  `OpenPaymentMethodsUseCaseTest` (tenant-scoped read).

## Tasks

1. Fix the out-of-transaction read and prove it with a test that runs under the tenant scope hook.
2. Introduce the two typed payload records; replace both decoders; make the malformed policy explicit and observable.
3. Make `Payment.succeed` atomic; update every caller and the state-machine test.
4. Log the billing-cycle failure cause; add a test that a failing wallet does not abort the other wallets.
5. Remove the duplicate reservation read; keep the lock order unchanged.
6. Replace the duplicated SHA-256 and the `"stripe"` actor literal.
7. Drop the extra transaction in `CreateSubscriptionUseCase`.
8. Add focused use-case tests for the money path and settlement.

## Verification

- `cd billing && ./gradlew :contexts:money:test :contexts:payment:test :contexts:invoicing:test`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*MoneyIntegrationTest' --tests '*PaymentIntegrationTest' --tests '*PostpaidCycleIntegrationTest'`
- `cd billing && ./gradlew build`

## Success criteria

- [ ] `OpenPaymentMethodsUseCase` returns the existing Stripe customer on a second call under the tenant scope.
- [ ] A malformed `charge.succeeded`/`payment.succeeded` produces one logged, counted record and the documented
      inbox/DLQ outcome; no unchecked cast remains in production.
- [ ] `Payment` cannot reach `SUCCEEDED` without a ledger entry id.
- [ ] A failed wallet in the cycle is logged with its id and period; the other wallets still bill.
- [ ] The charge use case reads the reservation once.

## Risks

The malformed-event policy interacts with the shared transaction that also writes webhook deliveries. Mitigation:
decide the policy against the dispatcher (`AmqpInboundDispatcher`) and `RecordDeliveriesUseCase` together; test the
poison path.
