---
phase: 3
title: "Phase 3: Persistence and boot wiring"
status: done
priority: P1
effort: "M"
dependencies: [2]
---

# Phase 3: Persistence and boot wiring

## Goal

Persist coupons, promotion codes and redemptions, wire the `coupon` module into the boot app, and satisfy every build
gate that a new context triggers, before any behaviour is added.

## Context

- Migrations live in `billing/apps/app-boot/src/main/resources/db/migration`; the latest is `V26`. Every tenant table
  calls `call enable_tenant_rls('<table>')` (see `V6__money.sql`, `V13__subscriptions.sql`).
- Cross-context foreign keys are the convention (`V8__pricing_usage.sql` references wallets, charges and customers).
- jOOQ code is generated from the migrations and committed: `JooqSchemaTest` fails when it is stale.
- ArchUnit maps each generated table to its owning context (`ArchitectureRules.TABLE_OWNER`); only the owner may use it.
- Repositories are plain classes in the adapter layer, constructed in a boot `@Configuration` (see `MoneyConfiguration`).

## Files

- Create: `billing/apps/app-boot/src/main/resources/db/migration/V27__coupons.sql`
- Create: `billing/contexts/coupon/src/application/java/dev/ngb/billing/coupon/application/{coupon,redemption}/port/*Repository.java`
  and `.../query/*ReadRepository.java` (ports); `.../adapter/{coupon,redemption}/persistence/Jooq*.java`
- Create: `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/config/CouponConfiguration.java`
- Modify: `billing/apps/app-boot/build.gradle.kts` (`implementation(project(":contexts:coupon"))`)
- Modify: `billing/apps/app-boot/src/test/java/dev/ngb/billing/architecture/ArchitectureRules.java` (`TABLE_OWNER`: `Coupons`,
  `PromotionCodes`, `CouponRedemptions` → `coupon`)
- Regenerate and commit: `billing/libs/jooq-schema/src/main/java/dev/ngb/billing_jooq/**`
- Create: repository integration tests under `billing/contexts/coupon/src/test/...` using the existing Postgres test support

## Schema (shape, not final column names)

- `coupons`: id, tenant_id, name, benefit type and value (basis points or amount plus currency), window count and unit,
  max applications, max total discount, scope arrays (kinds, plan ids, metrics), redeem_by, max_redemptions,
  max_per_customer, min_charge, max_discount_per_application, budget, `budget_used` (default 0), `redemptions_count`
  (default 0), stackable, status, created_by, created_at, deactivated_at. A check enforces the total bound.
- `promotion_codes`: id, tenant_id, coupon_id, code (stored upper-case, unique per tenant), max_redemptions, redeem_by,
  `redeemed_count`, status, created_by, created_at.
- `coupon_redemptions`: id, tenant_id, coupon_id, code_id (nullable), wallet_id, customer_id, status, redeemed_at,
  ends_at, `applications_used`, `discount_total`, end_reason, ended_at, actor, idempotency_key, request_hash. Unique on
  `(tenant_id, idempotency_key)`; a partial unique index on `(tenant_id, wallet_id, coupon_id) where status = 'ACTIVE'`.
- Every table has row-level security; money columns are `bigint` minor units with checks (`>= 0`, percent range).

## Steps

1. Back up any real database before applying the migration outside Testcontainers (the repository's tests use a
   throwaway container; a developer's compose database needs a `pg_dump` first).
2. Write `V27__coupons.sql`, then run the jOOQ generator with
   `cd billing && ./gradlew :apps:app-boot:test --tests '*JooqSchemaTest' -Djooq.update=true` and commit the output.
3. Define the repository ports in `application` and implement them in `adapter/*/persistence` with jOOQ. Provide:
   - `lockById` for a coupon and for a code (`for update`), `lockActiveByWallet` (redemption rows ascending by id, `for update`)
   - `redeemedCountUp` as one conditional statement (`update ... set redemptions_count = redemptions_count + 1 where
     redemptions_count < max_redemptions`) returning whether a row changed; the same for the code's counter
   - `addBudgetUse` as one conditional statement against `budget`
   - paged reads for the management API
4. Add `CouponConfiguration` and the dependency; run the ArchUnit and Modulith tests and fix placement violations.
5. Write repository tests: round trip, RLS (another tenant sees nothing), conditional counters stop at the limit under
   concurrent calls, the partial unique index refuses a second ACTIVE redemption of one coupon on one wallet.

## Todo

- [x] `V27__coupons.sql` written and applied by the test containers
- [x] jOOQ code regenerated and committed; `JooqSchemaTest` green
- [x] Ports and jOOQ adapters
- [x] `CouponConfiguration`, build dependency, `TABLE_OWNER`
- [x] Repository tests including RLS and concurrent counters

## Verification

- `cd billing && ./gradlew :contexts:coupon:test`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*JooqSchemaTest' --tests '*ArchitectureTest' --tests '*ModulithVerificationTest'`

## Risks

- A migration name or test name that mentions a plan or phase breaks the repository convention: name by what it holds.
- The generated jOOQ output is large; commit it in the same commit as the migration so the schema test passes on a clean checkout.

## Security considerations

Row-level security is on from the first migration. The application database role gets only the privileges the other
tenant tables have; no table here needs more.
