---
phase: 8
title: "Contract constants and naming"
status: pending
priority: P2
effort: M
dependencies: [3, 4, 5, 7]
---

# Phase 8: Contract constants and naming

## Goal

One vocabulary for event types, envelope fields, payload keys, HTTP headers, permission codes, actor/target names and
metrics, with a build-time or test-time check that the vocabulary matches the contracts.

## Findings addressed

- Event types are declared three ways (`MoneyEventTypes`/`PaymentEventTypes`, nested `TYPE` on 16 records, literals in
  `WebhookPolicy`/`MessagingConfiguration`/`ReplicaEventHandler`/`ReplicaWriter`) and copied into five wiring points.
- Payload key `minorUnits`/`currency` is duplicated in five `money()` helpers; `payload(Object...)` has three
  null-handling variants; consumers read raw keys.
- Headers: `Idempotency-Key` ×33, `X-Request-Id` ×43, `If-Match` ×24, `Stripe-Signature` ×5, `Billing-*` re-declared
  in tenant; only `WebhookHeaders` exists.
- Platform permission codes are 13 raw `@RequiresPermission("...")` literals; no test compares them with
  `contracts/permissions/*.yaml`.
- Actor/target/idempotency-key prefixes: 22 `<target>/` forms and 24 `<key>:` forms inline; `tenant-system/` and
  `service/` already exist as constants in `core-suite` but are duplicated; `"stripe"` vs `"system/stripe"`.
- Duplicate error messages (`"wallet not found"` ×14, `"customer not found"` ×12, tenant mapping ×5).
- Metric names differ for the same event (`outbox.delivered` vs `outbox.publish.delivered`); log labels are parsed by
  the smoke test but are literals.

## Files to create / modify

- Create `core/core-kernel/src/main/java/dev/ngb/core/http/HttpHeaders.java`:
  `IDEMPOTENCY_KEY = "Idempotency-Key"`, `REQUEST_ID = "X-Request-Id"`, `IF_MATCH = "If-Match"`,
  `STRIPE_SIGNATURE`, and the six `Billing-*` delivery headers. `WebhookHeaders` delegates to it.
- Create `core/core-kernel/src/main/java/dev/ngb/core/event/SuiteEventTypes.java` (or per-producer
  `EventTypes` classes) with every type constant. Keep producer-specific names in the producer's `api`; add a test
  that the constant set equals the schema-file set. Update `MessagingConfiguration`, `ReplicaEventHandler`,
  `ReplicaWriter`, `WebhookPolicy`, `EventContractIntegrationTest` and `SchemaEventTypeCatalog`.
- Create `billing/contexts/shared-kernel/src/main/java/dev/ngb/billing/sharedkernel/event/MoneyPayload.java` and
  `MoneyEventPayload` helper; replace the five `money()` copies and the three `payload()` variants. Convert each
  event's payload to a record where the shape is not already stable; serialize with one mapper.
- Create `core/core-kernel/src/main/java/dev/ngb/core/names/ActorNames.java`,
  `AggregateTargets.java`, `IdempotencyKeys.java`; replace inline prefixes; document `/` for targets and `:` for keys.
- Create `platform/.../api/PlatformPermissions.java` (or put it in `access/api`) with all catalog codes; replace the
  controller literals. Add a test scanning `@RequiresPermission` values and comparing them with the YAML catalogs for
  platform and billing; do the same for `PortalPermission`.
- Create `core/core-kernel/src/main/java/dev/ngb/core/observability/MetricNames.java` with the `.outbox.*`,
  `.webhook.*`, `.job.*` names; make both relays use `outbox.delivered` and `outbox.publish.delivered` according to
  the documented distinction.
- Create `.../Messages.java` per context (or `Errors` factories) for the repeated not-found messages; use them in
  use cases and controllers.
- Modify `billing/contexts/webhook/.../adapter/.../json/JacksonEventBodyWriter.java` to consume the canonical
  `EventDocument` from phase 5 and the header constants.
- Modify `tenant/modules/webhook/.../adapter/.../web/WebhookController.java` to use `HttpHeaders`.
- Modify `billing/contexts/payment/.../PaymentSettlement.java` to use `PaymentActors.STRIPE`.

## Tasks

1. Add the test that compares each producer's schema filenames with its `EventTypes` constants; fail first.
2. Move every type literal to a constant; remove the duplicate lists from wiring.
3. Introduce `MoneyPayload`; make all producers/consumers use it and one null policy.
4. Introduce `HttpHeaders`; replace header literals in controllers, filters, tests and the webhook writer.
5. Introduce `PlatformPermissions`; add the reflection test against the YAML catalog.
6. Introduce `ActorNames`, `AggregateTargets`, `IdempotencyKeys`; replace inline forms; fix `"stripe"`.
7. Introduce `MetricNames`; unify the two relay metric names.
8. Replace repeated messages with factories; keep the API text unchanged where it is already asserted.
9. Run the grep gates in the plan's verification section.

## Verification

- `cd core && ./gradlew build`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*EventContractIntegrationTest' --tests '*PermissionCatalogTest' --tests '*ArchitectureTest'`
- `cd platform && ./gradlew :apps:app-boot:test --tests '*PermissionCatalogTest'`
- `cd tenant && ./gradlew build`
- `grep -rn '"Idempotency-Key"\|"X-Request-Id"\|"If-Match"' billing platform tenant --include='*.java'` returns no
  production hit outside `HttpHeaders`.
- `grep -rn 'Map.of("minorUnits"' billing --include='*.java'` returns only `MoneyPayload`.

## Success criteria

- [x] The EventTypes constants, schema files, exports, queue bindings and `accepts` switches are one set.
- [x] One `MoneyPayload` helper; one null policy for optional payload fields.
- [x] All HTTP header names come from `HttpHeaders`.
- [x] Every `@RequiresPermission` code exists in the contract catalog (test-enforced).
- [x] Aggregate targets and idempotency keys are built through helpers; no inline prefix remains in application code.
- [x] One metric name per relay event.

## Risks

Renaming a message or header is externally visible. D9 allows one breaking release: change the wire value and the
contract file in the same commit, update every in-repo producer/consumer and the smoke assertions, and deploy the
suite together.
