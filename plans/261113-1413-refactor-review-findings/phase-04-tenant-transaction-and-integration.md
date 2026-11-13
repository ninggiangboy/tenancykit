---
phase: 4
title: "Tenant transaction and integration"
status: pending
priority: P1
effort: M
dependencies: [1]
---

# Phase 4: Tenant transaction and integration

## Goal

Make the sample tenant's write flows atomic, make its Billing calls compensation-safe, derive retry-safe idempotency
keys, verify webhook signatures before parsing, and turn its stringly order/webhook states into explicit rules.

## Findings addressed

- Only `CreateOrderUseCase:133` calls `unitOfWork.transactional`; `RegisterUserUseCase:55-60`,
  `ReceiveWebhookUseCase:58-62`, `ManageOrderUseCase`, `ExecuteOrdersUseCase`, `ManageWalletSettingUseCase` commit
  2–5 times per flow.
- `CreateOrderUseCase:94-141` calls Billing (subscribe/reserve) before persisting, with no compensation;
  `ManageOrderUseCase:37-40` cancels Billing then saves locally.
- `TopUpUseCase:35` and `CreateOrderUseCase:94` use `UUID.randomUUID()` for idempotency material.
- `WebhookController.java:45` parses JSON before `ReceiveWebhookUseCase` verifies the HMAC over the raw bytes,
  contradicting its own Javadoc.
- `ManageWalletSettingUseCase:64-75` hard-codes `"USD"` and catches `UpstreamUnavailable | Conflict | BadRequest`
  with an empty body.
- `Order.transition` only rejects terminal states, so `RUNNING → QUEUED` and `QUEUED → COMPLETED` pass;
  `JooqOrderQueueRepository.finish/requeue` updates by order id without a status guard.
- `AuthController.java:48` decides "first registrant becomes ADMIN" in the web adapter with a TOCTOU race.
- `CatalogController` has no `@RequiresPermission`; `WalletSettingController:51` uses `CUSTOMER_MANAGE` for a
  customer's own setting; `CATALOG_MANAGE`/`SETTINGS_MANAGE` are unused.
- `RegisterUserUseCase:43` checks email existence on the raw value before normalizing.

## Files to create / modify

- `tenant/modules/identity/src/application/.../user/command/RegisterUserUseCase.java`,
  `tenant/modules/webhook/src/application/.../event/command/ReceiveWebhookUseCase.java`,
  `tenant/modules/resource/src/application/.../order/command/ManageOrderUseCase.java`,
  `tenant/modules/execution/src/application/.../queue/command/ExecuteOrdersUseCase.java`,
  `tenant/modules/customer/src/application/.../wallet/command/ManageWalletSettingUseCase.java`: inject
  `UnitOfWork`, wrap the whole decision.
- `tenant/modules/resource/src/application/.../order/command/CreateOrderUseCase.java`: persist a `PENDING` order with
  the deterministic reference first, call Billing, then commit the final state; add a compensation/expiry path.
- `tenant/modules/customer/src/application/.../billing/command/TopUpUseCase.java`,
  `CreateOrderUseCase.java`: take the key from the request header or a stable business key; reject a write without one.
- `tenant/modules/webhook/src/adapter/.../event/web/WebhookController.java`: verify `WebhookSignature` over
  `byte[]` before `json.readValue`; the use case receives verified bytes.
- `tenant/modules/customer/src/application/.../wallet/command/ManageWalletSettingUseCase.java`: currency from
  configuration; log and rethrow (or record a retryable state) instead of an empty catch.
- `tenant/modules/resource/src/domain/order/Order.java`: explicit allowed-transition map; `requeued()` distinct from
  `resumed()`; table-driven test for every edge.
- `tenant/modules/resource/src/adapter/.../order/persistence/JooqOrderQueueRepository.java`: status predicate on
  `finish`/`requeue`; assert one row updated.
- `tenant/modules/identity/src/application/.../user/command/RegisterUserUseCase.java` + `AuthController`: move the
  first-admin policy into the use case, normalize the email before the existence check, make the decision atomic.
- `tenant/modules/catalog/src/adapter/.../plan/web/CatalogController.java`,
  `.../customer/adapter/.../WalletSettingController.java`,
  `tenant/modules/identity/src/api/.../security/Permission.java`: annotate endpoints; use `SETTINGS_MANAGE` for the
  customer's own setting; delete unused codes.
- `tenant/apps/app-boot/src/main/java/dev/ngb/tenant/boot/config/*`: add the wallet currency property.
- Tests: `RegisterUserUseCaseTest`, `ReceiveWebhookUseCaseTest`, `CreateOrderUseCaseTest`, `ManageWalletSettingUseCaseTest`,
  `OrderTest`, `JooqOrderQueueRepositoryIntegrationTest`.

## Tasks

1. Add `UnitOfWork` to the five write flows; write the rollback test first (force the second write to fail).
2. Reorder order creation: local PENDING row, Billing call, final commit; add expiry/compensation for a crash.
3. Replace random keys with request/stable keys; reject a missing key on writes; use `IdGenerator` for internal ids.
4. Verify the webhook signature before deserialization; add a forged-body test that never reaches `readValue`.
5. Replace the `"USD"` literal and empty catch; log with user id and outcome.
6. Implement the explicit order transition map and the queue status guard; add the full edge matrix.
7. Move first-admin policy into the use case and normalize the email once.
8. Fix the permission annotations and delete the unused codes.

## Verification

- `cd tenant && ./gradlew build`
- `cd tenant && ./gradlew :apps:app-boot:test --tests '*Webhook*IntegrationTest' --tests '*Order*IntegrationTest' --tests '*Execution*IntegrationTest'`
- `cd tenant && ./gradlew :modules:resource:test :modules:identity:test :modules:customer:test`
- `cd tenant && ./gradlew checkNoBillingDependency build`

## Success criteria

- [ ] A failure in the second write of each flow leaves no partial state.
- [ ] Retrying a top-up or order with the same key never creates a second Billing reservation/subscription.
- [ ] A tampered body is rejected before JSON parsing.
- [ ] The order state machine accepts only the documented edges; a stale worker cannot finish a re-claimed row.
- [ ] Two simultaneous first registrations cannot both become ADMIN.

## Risks

Wrapping Billing calls in a local transaction must not hold a database lock across HTTP. Mitigation: the order-first
pattern keeps the remote call outside the final transaction; document the state machine and the expiry job.
