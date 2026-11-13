---
phase: 9
title: "Domain status, type and configuration constants"
status: pending
priority: P2
effort: M
dependencies: [4, 8]
---

# Phase 9: Domain status, type and configuration constants

## Goal

Replace stringly-typed statuses and pricing models with enums, and move every repeated property key behind a typed
configuration record.

## Findings addressed

- Tenant `Order`/`ExecutionOrders`/`PlanSnapshot` carry `pricingModel`, `status`, `metric` as `String`; application
  code branches on `"SUBSCRIPTION"`, `"HYBRID"`, `"EVENT_BASED"`, `"RETIRED"`, `"QUEUED"` while
  `catalog.domain.plan.PricingModel` already exists and is flattened at the api boundary.
- Tenant `WebhookEvent.status`/`decision` are `String` with `"RECEIVED"`, `"PROCESSED"`, `"RESUMED"`, `"SUSPENDED"`;
  `WebhookPolicy.Decision` is an enum converted to a string at the boundary.
- Adapters mix `.name()` and literals: `JooqWebhookDeliveryRepository:73` writes `"DEAD"`; tenant
  `JooqOrderQueueRepository` writes `"PENDING"`, `"RUNNING"`, `"DONE"`; queries compare `"ACTIVE"`, `"PAST_DUE"`,
  `"PENDING"`, `"SUCCEEDED"`.
- `billing.portal.enabled` appears 11 times, `billing.jobs.enabled` 9, `billing.tenancy.base-domain` 4; some features
  have a configuration record, others use `@Value`/`@ConditionalOnProperty` literals.
- `ManageWalletSettingUseCase` hard-codes `"USD"`; `Currency`/`Money` differ between billing and tenant.

## Files to create / modify

- Create `tenant/modules/resource/src/api/java/dev/ngb/tenant/resource/api/order/OrderStatus.java` (or reuse the
  catalog enum plus an order enum) and `.../PricingModel.java` exposed from `catalog/api`; change
  `Order`, `ExecutionOrders`, `OrderView`, `PlanSnapshot`, `CreateOrderUseCase`, `ExecuteOrdersUseCase`,
  `JooqOrderRepository`, `JooqOrderQueueRepository`.
- Create `tenant/modules/webhook/src/api/java/dev/ngb/tenant/webhook/api/event/WebhookDeliveryStatus.java` and
  `WebhookDecision.java`; use them in `WebhookEvent`, `ReceiveWebhookUseCase`, `WebhookPolicy`, the repository and
  views; persist `.name()`.
- `billing/libs/shared-kernel/.../money/Money.java`, `tenant/libs/shared-core/.../money/Money.java`,
  `tenant/modules/billing-client/src/api/.../model/Amount.java`: choose one representation; the recommended target is
  Billing's `Money` + `Currency` exposed from `billing-client/api`, and the tenant `Money` becomes a thin adapter or
  is deleted. Update the docs sentence that claims "money used by every module".
- Create `billing/apps/app-boot/.../config/PortalProperties.java`, `JobsProperties.java`, `WebhookProperties.java`,
  `SuiteProperties.java` (`@ConfigurationProperties` records); replace `@Value`/`@ConditionalOnProperty` literals and
  their test copies.
- Add the tenant wallet currency property to `tenant/apps/app-boot/src/main/resources/application.yml` and use it in
  `ManageWalletSettingUseCase`.
- Replace literal status writes/comparisons in adapters with `EnumType.name()` or generated constants; add a rule or
  checkstyle entry if practical.

## Tasks

1. Add failing tests for an invalid pricing model and an invalid order transition.
2. Introduce the enums, change the api snapshots and domain types, then the adapters; regenerate/update views.
3. Convert the webhook status/decision to enums; keep the persisted strings unchanged.
4. Unify money; delete the duplicate representation and update the docs.
5. Create the configuration records; replace keys in production and tests; keep defaults identical.
6. Move the wallet currency to configuration.
7. Sweep adapters for literal status values; replace with enum names.

## Verification

- `cd tenant && ./gradlew build`
- `cd billing && ./gradlew build`
- `cd tenant && ./gradlew :apps:app-boot:test --tests '*Order*IntegrationTest' --tests '*Webhook*IntegrationTest'`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*Runtime*IntegrationTest' --tests '*Portal*IntegrationTest'`
- `grep -rn '"SUBSCRIPTION"\|"HYBRID"\|"EVENT_BASED"\|"QUEUED"' tenant --include='*.java'` returns no production hit.

## Success criteria

- [ ] No production `switch`/`case` compares a raw pricing-model, order-status or webhook-decision string.
- [ ] Adapters persist enum `.name()`; no raw status literal remains in a production adapter.
- [ ] One money type for the suite's REST and event contracts.
- [ ] Every repeated feature flag has one properties record; no raw key outside `application.yml`/tests.
- [ ] The wallet currency is configurable and defaults to today's `USD`.

## Risks

Exposing a domain enum through `api` can leak domain types into read models. Mitigation: expose a small api enum
(same values) rather than the domain class, or relax `ALLOWED_QUERY_DOMAIN_TYPES` with a documented exception.
