---
phase: 4
title: "Billing version columns, ETag and If-Match"
status: done
priority: P2
effort: "1d"
dependencies: [2]
---

# Phase 4: Billing version columns, ETag and If-Match

## Goal

Billing customers and webhook endpoints get a version, a row lock on update, an `ETag`, and an optional `If-Match`.

## Files to create / modify

- Create `billing/apps/app-boot/src/main/resources/db/migration/V24__resource_versions.sql`:
  `alter table customers add column version bigint not null default 1;` and the same for `webhook_endpoints`. A
  constant default is a metadata-only change in PostgreSQL 11+, with no table rewrite. The `migrate` profile's 3s
  `lock_timeout` and retry already cover the brief `ACCESS EXCLUSIVE` lock.
- Regenerate `billing/libs/jooq-schema`:
  `cd billing && ./gradlew :apps:app-boot:test --tests '*JooqSchemaTest' -Djooq.update=true`. Commit the result.
- Domain:
  - `billing/contexts/customer/src/domain/java/dev/ngb/billing/customer/domain/customer/Customer.java`: add `version`,
    bumped in `changeContact` and `deactivate` only when something changes.
  - The `WebhookEndpoint` aggregate in `billing/contexts/webhook/src/domain/...`: bump in `reconfigure`,
    `changeStatus` and secret rotation. Rotation is not in the If-Match scope, but it changes the row, so the next
    conditional PATCH must see a new version.
- Repositories:
  - `CustomerRepository` and `JooqCustomerRepository`: add `lockById` (`forNoKeyUpdate()`). `update` writes `version`,
    guarded with `and version < :new`, and throws when it updates 0 rows.
  - `WebhookEndpointRepository` and its jOOQ adapter: the same.
  - Read views and response DTOs gain `version` only where the controller needs it for the `ETag`. Do not add
    `version` to the JSON body, so the response schema stays unchanged.
- Use cases:
  - `ManageCustomerUseCase.update` and `deactivate` take `ExpectedVersion`. `requireVisible` switches to `lockById`;
    keep the `isMappedTo` check before the lock, so an invisible id is never locked. Check the version after the lock
    and before `changeContact`.
  - `ManageEndpointUseCase.update` does the same. `rotateSecret` locks but takes no `ExpectedVersion`.
- Controllers:
  - `CustomerController`: `PUT /{id}` and `POST /{id}/deactivate` read `If-Match` and return an `ETag`.
    `GET /{id}` returns an `ETag`.
  - `EndpointController`: `PATCH /webhook-endpoints/{id}` reads `If-Match` and returns an `ETag`.
    `GET /webhook-endpoints/{id}` returns an `ETag`.
- OpenAPI:
  - The `If-Match` parameter appears from `@RequestHeader` automatically.
  - Add an `OperationCustomizer` in `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/web/WebConfiguration.java`,
    next to `repeatableCreates()`, driven by a new marker annotation `@ConditionalUpdate`. Put the annotation in
    `core/core-kernel/.../version/` so Platform can adopt it later. The customizer adds a 412 response and an `ETag`
    response header to the marked operations.
  - Regenerate the contracts with
    `cd billing && ./gradlew :apps:app-boot:test --tests '*OpenApiContractTest' -Dopenapi.update=true`.
- Lock order: customer and webhook endpoint are single-row flows that lock nothing else. Add both to the table in
  phase 5.

## Tests

- `CustomerHttpIntegrationTest` and `WebhookHttpIntegrationTest`: add the conditional-update matrix from phase 3 (no
  header, current, stale, `*`, weak, malformed). A stale `If-Match` leaves no audit row.
- A Tenant System caller with a stale `If-Match` on a customer it did not map gets 404, not 412. Visibility is checked
  first, so the version of a customer it cannot see does not leak.
- Add a lost-update test using `UncommittedRace`: two PUTs with the same `If-Match`. The first gives 200 and the second
  gives 412.
- The existing `CustomerIdempotencyMigrationTest` and `RowLevelSecurityIntegrationTest` still pass.

## Verification

- `cd billing && ./gradlew :apps:app-boot:test --tests '*Customer*' --tests '*Webhook*' --tests '*JooqSchemaTest' --tests '*OpenApiContractTest'`
- `cd billing && ./gradlew build`
- `git diff contracts/openapi` shows only `If-Match`, the `ETag` header and 412 entries.

## Risks

- The migration on a large `customers` table: it is metadata-only, so the risk is only the lock queue, which the
  `migrate` profile already bounds. Back up the database before running it against any shared environment.

## Rollback

Revert the code. The added columns are harmless if left in place. If they must go, a follow-up migration drops them.
Never edit V24 after it has been applied.
