---
phase: 10
title: "Clean code and DDD sweep"
status: pending
priority: P2
effort: M
dependencies: [2, 3, 4, 5, 6, 7, 8, 9]
---

# Phase 10: Clean code and DDD sweep

## Goal

Remove the remaining duplication, move business rules out of controllers and adapters, split oversized
collaborators, and settle the DDD base-type question with one convention.

## Findings addressed

- Customer-visibility checks are copied in `ChargeUseCase:128`, `ReserveBudgetUseCase:87`,
  `ReleaseReservationUseCase:88`, `PaymentQueries:77`, `WalletQueries:116`.
- Response mappers duplicate aggregate/view branches (`CustomerController:50/59`, `WalletController:42/48`,
  `OrderController:38-70`, `UserController`, `AuthController`).
- Business rules in controllers: `RatingController:194` (events default + merge), `CustomerController:152-161`
  (`tenantSystemFor`), `BillingSessionController:71` (tenant-system-only), `TenantSystemController:172-182`
  (credential ownership — moved in phase 2), `AuthController:48` (first admin — moved in phase 4).
- Oversized constructors: `RequestRefundUseCase` (13), `InvoiceChargeUseCase` (12), `ReceiveStripeWebhookUseCase` (12),
  `ReconcileStripeUseCase` (11), `StripeEventProcessor` (11), `PaymentSettlement` (9/10),
  `CreateOrderUseCase` (11), `IssueTokenUseCase`/`ManageCredentialUseCase` (9).
- `EnableAppUseCase` vs `DisableAppUseCase` differ by ~12 lines; `EntitlementRows` already shows the right factoring.
- `AggregateRoot`/`Entity`/`ValueObject` are adopted by only `TenantSystem` and `WebhookEvent`; `pullEvents`/`register`
  are unused; 100+ aggregates are plain classes.
- Technical fields (`idempotencyKey`, `requestHash`, `version`) sit on five aggregates.
- Inline FQNs and dead helpers: `ServiceClients` (phase 2), `StaticTokenProvider.equals/hashCode`,
  `TokenSettings.forBaseUrl` + `FeignClients.stripTrailingSlash`, `new java.util.ArrayList<>()` in `SnapshotClient`,
  `java.util.Locale`/`java.util.Comparator` FQNs in controllers.
- `Payment.succeed`/`recordLedgerEntry` split is fixed in phase 3; keep the phase-3 test as the regression guard.

## Files to create / modify

- Create `billing/contexts/customer/src/application/.../service/CustomerVisibility.java` (or an `api` lookup method)
  and use it from the five call sites.
- Create one response factory per controller that accepts a narrow interface or a shared `record` of the common
  fields; delete the duplicate `of(...)` branches. For persistence, keep `Jooq*Repository.toDomain` and
  `Jooq*ReadRepository.toView` but extract shared column mapping where it is literal duplication.
- Move `RatingController` default/merge into `pricing.domain.metric.Measure`/`ChargeFromPriceLine`; add a domain test.
- Move `tenantSystemFor` into `CreateCustomerUseCase`/`ManageCustomerUseCase` (they already take the caller) and
  `BillingSessionController`'s check into `OpenBillingSessionUseCase`.
- Extract `RefundGateway`, `AuditPreamble` (actor/request id/tenant), `StripeEventPrefetch` collaborators to reduce
  the payment constructors below 8 dependencies; keep `UnitOfWork` in the use case.
- Merge `EnableAppUseCase`/`DisableAppUseCase` into one `SetAppEntitlementUseCase(boolean)` or a shared
  `AppDisposition` service; keep the same HTTP behaviour.
- Apply D3: keep `AggregateRoot`/`Entity`/`ValueObject` unchanged, and document in `docs/architecture.md` which two
  aggregates use `AggregateRoot`, that `pullEvents`/`register` are currently unused, and that plain classes plus
  `EventPublisher` are the convention elsewhere. No adoption or deletion.
- Replace inline FQNs and remove `StaticTokenProvider.equals/hashCode` if unused; collapse the trailing-slash helpers.
- Move the remaining domain-tech fields behind a `ReplayIdentity` value object on each aggregate that keeps them.

## Tasks

1. Extract `CustomerVisibility`; replace five copies; add a test for the tenant-system caller.
2. Collapse the duplicate response mappers; keep the JSON identical (assert with the existing HTTP tests).
3. Move the rating default/merge into the domain and move the two authorization checks into their use cases.
4. Extract the payment collaborators; assert each constructor is ≤8 dependencies.
5. Merge the entitlement use cases.
6. Document D3 (keep the base types, record the convention); change no usage.
7. Remove inline FQNs, dead equality, and duplicated trailing-slash logic.
8. Add a test that the API responses are byte-identical before/after the mapper refactor.

## Verification

- `cd billing && ./gradlew build`
- `cd platform && ./gradlew build`
- `cd tenant && ./gradlew build`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*HttpIntegrationTest' --tests '*ArchitectureTest'`
- `cd tenant && ./gradlew :apps:app-boot:test --tests '*HttpIntegrationTest'`
- `grep -rn 'private void requireVisible' billing --include='*.java'` returns only the shared collaborator.

## Success criteria

- [ ] One customer-visibility implementation.
- [ ] Controllers contain no business decision; they map, validate shape and call a use case.
- [ ] No use-case constructor exceeds eight dependencies without a documented reason.
- [ ] D3 is documented: the two base-type users, the unused `register`/`pullEvents`, and the plain-class convention.
- [ ] No inline FQN remains in production controllers/config.

## Risks

Refactoring response mappers can change JSON ordering or null handling. Mitigation: the HTTP tests assert the full
body; run them before and after each mapper change.
