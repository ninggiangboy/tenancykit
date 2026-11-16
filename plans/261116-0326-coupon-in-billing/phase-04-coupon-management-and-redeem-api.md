---
phase: 4
title: "Phase 4: Coupon management and redeem API"
status: done
priority: P1
effort: "L"
dependencies: [2, 3]
---

# Phase 4: Coupon management and redeem API

## Goal

Let Administrators and Tenant Systems create and deactivate coupons and codes, and redeem or attach a coupon to a
wallet, including the wallet-credit benefit and the `coupon.redeemed` event. Discount coupons are stored and redeemable
here but do not yet change any charge.

## Context

- Controllers are `@Profile("api")` `@RestController`s with `@RequiresPermission`, `@RepeatableCreate` and the
  `Idempotency-Key` header for creates (`ChargeController`, `InvoiceController`, `SubscriptionController`). State changes
  such as void and cancel are POST actions; follow `InvoiceController` and `SubscriptionController`.
- A Tenant System sees only the customers mapped to it: use `CustomerVisibility` and `CurrentActor.caller()` as
  `ChargeUseCase` does.
- Credits go through `CreditWallet` with a reference and an idempotency key (`CreditCommand`); no new ledger type.
- Permissions are a hand-written catalog (`contracts/permissions/billing.yaml`) and constants (`BillingPermissions`);
  `PermissionCatalogTest` requires the catalog, the constants and the endpoint annotations to be one set.
- OpenAPI is generated: after the controllers exist run
  `cd billing && ./gradlew :apps:app-boot:test --tests '*OpenApiContractTest' -Dopenapi.update=true` and commit both files.

## Files

- Create under `billing/contexts/coupon/src/application/java/dev/ngb/billing/coupon/application/`:
  `coupon/command/{CreateCouponUseCase,DeactivateCouponUseCase,AddPromotionCodeUseCase,DeactivatePromotionCodeUseCase}.java`,
  `redemption/command/{RedeemCouponUseCase,AttachCouponUseCase,RevokeRedemptionUseCase}.java`, query classes
- Create under `.../coupon/adapter/{coupon,redemption}/web/`: `CouponController`, `RedemptionController`
- Create: `.../coupon/domain/coupon/CouponEvents.java` and an `api` event payload type if another context needs it
- Modify: `billing/libs/shared-kernel/.../security/BillingPermissions.java` (3 constants and `CODES`),
  `contracts/permissions/billing.yaml` (3 entries, all `grantable_to_tenant_system: true`)
- Modify: `core/core-kernel/src/main/java/dev/ngb/core/event/SuiteEventTypes.java` (`COUPON_REDEEMED`,
  `COUPON_REDEMPTION_ENDED`, and the billing set); Create: `contracts/events/billing/coupon.redeemed.schema.json` and
  `contracts/events/billing/coupon.redemption-ended.schema.json`; Modify: `contracts/events/README.md`
- Modify: `billing/apps/app-boot/.../config/CouponConfiguration.java` (use cases and controllers)
- Regenerate: `contracts/openapi/billing.yaml`, `contracts/openapi/billing-tenant-system.yaml`

## API (confirm the final names when the controllers are written)

| Operation | Permission |
|---|---|
| `POST /api/v1/coupons` create (terms, benefit, duration, scope, limits, stackable, optional first code) | `billing:coupon:manage` |
| `GET /api/v1/coupons`, `GET /api/v1/coupons/{id}` | `billing:coupon:read` |
| `POST /api/v1/coupons/{id}/deactivate` (`revokeRedemptions` optional, default false) | `billing:coupon:manage` |
| `POST /api/v1/coupons/{id}/codes` (code optional: generated when absent), `GET .../codes`, `POST /api/v1/coupon-codes/{id}/deactivate` | `manage` / `read` |
| `POST /api/v1/wallets/{walletId}/coupon-redemptions` with `{code}` | `billing:coupon:redeem` |
| the same endpoint with `{couponId}` (attach, bypasses code limits) | `billing:coupon:manage` |
| `GET /api/v1/wallets/{walletId}/coupon-redemptions`, `GET /api/v1/coupon-redemptions/{id}` | `billing:coupon:read` |
| `POST /api/v1/coupon-redemptions/{id}/revoke` | `billing:coupon:manage` |

## Behaviour to implement

1. Creating a coupon validates through the domain (mandatory total bound, currency, ranges) and stores `created_by` from
   the actor. Terms are never edited afterwards.
2. Redeem lock order: wallet, then coupon row, then code row, then the new redemption. Visibility is checked after the
   wallet lock exactly as `ChargeUseCase` does.
3. Checks, all under those locks: coupon and code ACTIVE, not past `redeemBy`, the coupon's and code's redemption
   counters (conditional update), the per-customer limit across the customer's wallets, currency equals the wallet's,
   no other ACTIVE redemption of this coupon on this wallet (409).
4. Unknown, inactive, expired and exhausted codes all answer 404 with one message.
5. Idempotency: a repeat with the same key returns the first result (200 versus 201); the same key with a different
   body is 409; use the repository's request-hash helper.
6. Wallet-credit coupon: the redemption is created ENDED with one application in the same transaction as the credit,
   through `CreditWallet` with key `coupon-redemption:<id>` and reference naming the coupon.
7. `coupon.redeemed` is published through the outbox in the same transaction: `{redemptionId, couponId, codeId?, walletId,
   customerId, benefit, maxApplications?, maxDiscountTotal?, endsAt?, creditAmount?, actor}`. Billing's webhook catalog is
   read from the schema folder (`SchemaEventTypeCatalog`), so both coupon events are subscribable with no webhook change.
   `coupon.redemption-ended` `{redemptionId, couponId, walletId, customerId, reason, applicationsUsed, discountTotal,
   endedAt}` is published in the transaction that writes an ENDED or REVOKED state: here by revoke (`REVOKED`) and by
   deactivation with `revokeRedemptions` (`COUPON_DEACTIVATED`, one per redemption); phase 5 adds `EXHAUSTED`. A
   wallet-credit redemption, created ENDED, publishes only `coupon.redeemed`. Reaching `endsAt` writes nothing and so
   publishes nothing; the Tenant has `endsAt` from `coupon.redeemed`.
8. Revoke ends the redemption with reason `REVOKED` and releases nothing else; it is idempotent (a repeat publishes no
   second event).
9. Deactivating a coupon with `revokeRedemptions` ends every ACTIVE redemption of it in one transaction, wallet rows
   untouched.

## Steps

1. Add permission constants, catalog entries and the event type with its schema (these three move together so the
   existing consistency tests stay green).
2. Before relying on `CreditWallet` inside the redeem transaction, read how `UnitOfWork.transactional` nests and confirm the credit re-locks a
   wallet the caller already holds without waiting; then implement use cases against the ports from phase 3; unit-test them with in-memory fakes where the repository has one,
   otherwise with the Postgres test support.
3. Write the controllers; add them to `CouponConfiguration`.
4. Integration tests through `HttpWorld`: create, redeem by code, attach, revoke, credit exactly once, idempotent replay,
   Tenant System limited to mapped customers, another tenant sees 404.
5. Regenerate the OpenAPI files and commit them.

## Todo

- [x] Permissions, catalog and `PermissionCatalogTest` green
- [x] Use cases and controllers
- [x] Credit benefit through `CreditWallet`
- [x] `coupon.redeemed` and `coupon.redemption-ended` events, schemas and suite constants; a webhook endpoint can
      subscribe to both
- [x] Integration tests, including the parallel redeem race (20 requests, 1 left, exactly 1 succeeds)
- [x] Generated OpenAPI committed

## Verification

- `cd billing && ./gradlew :contexts:coupon:test :apps:app-boot:test --tests '*PermissionCatalogTest' --tests '*OpenApiContractTest' --tests '*Coupon*'`
- A schema-to-constant test for event types exists today; it must stay green with both coupon events added.

## Risks

- `core/` is shared with other apps: the only change allowed there is the new constant; run the core tests.
- Code generation must use a non-ambiguous alphabet and enough length that guessing is impractical; reuse the repository's
  existing secure random helper if there is one.

## Security considerations

A Tenant System can create value, so every coupon must carry a total bound and the creating actor is stored. Redeem by
code and attach by coupon id are separate permissions. Responses never reveal whether a code exists, is inactive or is
exhausted.
