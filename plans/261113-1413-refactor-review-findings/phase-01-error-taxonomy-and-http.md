---
phase: 1
title: "Error taxonomy and HTTP mapping"
status: pending
priority: P1
effort: M
dependencies: []
---

# Phase 1: Error taxonomy and HTTP mapping

## Goal

One error taxonomy in `core-kernel`, one RFC 9457 mapping base in `core-spring`, and every service reporting the same
status and problem shape for the same failure.

## Findings addressed

- `platform/contexts/access/src/application/**/exception/*.java` extends bare `RuntimeException` and
  `platform/apps/app-boot/.../ApiExceptionHandler.java` does not map them, so role/user/permission errors return 500.
- `tenant/libs/shared-core/.../error/*` re-declares `NotFoundException`, `ConflictException`, `UnauthorizedException`
  and `UpstreamUnavailableException` with the same names as `core-kernel`, plus `BadRequestException`,
  `ForbiddenException` and `PaymentRequiredException`. Four taxonomies exist across the repo.
- Billing, platform and tenant handlers have different coverage: only billing catches `Exception`, only billing maps
  `PreconditionFailed` and `IdempotencyConflict` consistently, tenant has no catch-all, platform has no
  `IllegalStateException` mapping.
- Bare `orElseThrow()` produces `NoSuchElementException` and 500 in `RequestRefundUseCase:109,118`, `CheckoutOpener:51,58`,
  `CreateSubscriptionUseCase:100`, `EntitlementRows.lockOrInsert`.
- `SuiteEntitlements.requireEnabled` throws `NotFoundException("app-not-enabled")` while every other message is a
  sentence.

## Files to create / modify

- Create `core/core-kernel/src/main/java/dev/ngb/core/error/BadRequestException.java`,
  `PaymentRequiredException.java` (both `RuntimeException`, `serialVersionUID`).
- Modify `core/core-kernel/src/main/java/dev/ngb/core/error/UnauthorizedException.java`: document that it means
  "this deployment/caller has no usable credential", not "the user is not signed in".
- Create `core/core-spring/src/main/java/dev/ngb/core/spring/web/ProblemDetails.java` (one `ProblemDetail` factory:
  title/detail/type/status) and `CoreExceptionHandlers.java` (`@RestControllerAdvice` handling `NotFound`, `Conflict`,
  `IdempotencyConflict`, `BadRequest`, `PaymentRequired`, `AccessDenied`, `PreconditionFailed`, `IllegalArgument`,
  `IllegalState`, `PessimisticLockingFailure`, `DataIntegrityViolation`, catch-all `Exception`).
- Modify `billing/apps/app-boot/.../web/ApiExceptionHandler.java`,
  `platform/apps/app-boot/.../web/ApiExceptionHandler.java`,
  `tenant/apps/app-boot/.../web/ApiExceptionHandler.java` to extend or import `CoreExceptionHandlers` and keep only
  service-specific exceptions (`InsufficientFunds`, Stripe provider errors, `InvalidPortalSession`, tenancy codes,
  `PaymentRequired`).
- Delete `tenant/libs/shared-core/src/main/java/dev/ngb/tenant/shared/error/*` after migrating every import to
  `dev.ngb.core.error`; keep `tenant/shared/money` until phase 9.
- Modify platform access use cases/exceptions: replace the four local exceptions with `NotFoundException`,
  `ConflictException`, `BadRequestException`, or map them explicitly in the handler if a distinct type is retained.
- Modify `billing/contexts/payment/.../RequestRefundUseCase.java`, `.../CheckoutOpener.java`,
  `billing/contexts/invoicing/.../CreateSubscriptionUseCase.java`,
  `platform/contexts/tenancy/.../EntitlementRows.java` to replace bare `orElseThrow()` with a typed, messaged throw.
- Modify `core/core-suite/.../SuiteEntitlements.java` message to "this tenant has not enabled <app>".
- Create `core/core-spring/src/test/java/dev/ngb/core/spring/web/CoreExceptionHandlersTest.java`.
- Modify `billing/.../ApiExceptionHandlerTest.java`, add `platform/.../ApiExceptionHandlerTest.java`,
  `tenant/.../ApiExceptionHandlerTest.java`.

## Tasks

1. Add `BadRequestException` and `PaymentRequiredException` to `core-kernel`; add a test for each.
2. Build `ProblemDetails` + `CoreExceptionHandlers`; keep the Billing-specific 502/409/503 behaviours identical
   (provider rejected/unavailable, lock contention with `Retry-After`, `InsufficientFunds` 402).
3. Migrate tenant imports off `dev.ngb.tenant.shared.error`; delete the package; compile the tenant build.
4. Replace platform access local exceptions with core types, or map each one explicitly; assert 404/409/422 in an
   integration test.
5. Replace every bare `orElseThrow()` in application code with `NotFoundException`/`IllegalStateException` carrying
   the id.
6. Normalize messages: a sentence, no slug-only messages, no leaked internals in 5xx; document the rule in
   `docs/system-specification.md` or the closest error section.
7. Ensure every handler returns `application/problem+json` and the same `status/title/detail` shape.

## Verification

- `cd core && ./gradlew build`
- `cd platform && ./gradlew :apps:app-boot:test --tests '*ApiExceptionHandlerTest' --tests '*UserHttpIntegrationTest'`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*ApiExceptionHandlerTest'`
- `cd tenant && ./gradlew build`
- `grep -rn "dev.ngb.tenant.shared.error" tenant --include='*.java'` returns nothing.

## Success criteria

- [x] A role-not-found, user-not-found, unknown-permission and duplicate-email request each return 404/404/422/409,
      not 500, with a `problem+json` body.
- [x] The same failure produces the same status in billing, platform and tenant.
- [x] No production code calls `orElseThrow()` without a supplier that names the missing id.
- [x] The tenant build has no `shared.error` package.

## Risks

Changing a status code is an API change. Mitigation: inventory every exception-to-status pair before the change; keep
the existing billing statuses (402/409/412/503) and record only the platform/tenant corrections in the phase notes.
