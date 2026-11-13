---
phase: 11
title: "Test and documentation hardening"
status: pending
priority: P1
effort: M
dependencies: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
---

# Phase 11: Test and documentation hardening

## Goal

Close the evidence gaps that let the findings survive: add the missing unit and security tests, split the giant
integration tests, make test packages and coverage claims true, and correct every document that describes behaviour
the code does not have.

## Findings addressed

- `platform/contexts/*` has zero unit tests; `billing/contexts/usage` has none; `ChargeUseCase`, `LedgerPosting`,
  `ExpireReservationsUseCase`, `PaymentSettlement` have no focused tests.
- Core machinery is untested: `PermissionInterceptor`, `JooqAuditLog`, `SpringUnitOfWork`,
  `SuiteTenantFilter`, `SuiteEntitlementFilter`, `SuiteActorConverter`; `core-kernel`/`core-spring` have no boundary
  test despite the docs claiming every core module does.
- `PaymentIntegrationTest` is 1,140 lines with 56 tests mixing top-up, webhook, refund and reconciliation.
- Tenant tests do not mirror production packages; read repositories have no integration test despite
  `docs/tenant-system.md` claiming each does.
- Docs drift: `web/README.md` is a placeholder while the decision ledger lists a React monorepo; `docs/architecture.md`
  calls the UoW discipline "a build rule" with no rule; the core ArchUnit claim ("each module") is 5/7; the messaging
  and events READMEs claim the AMQP and webhook documents are identical; `SuiteJwtDecoders.audience` says "exactly"
  but uses `contains`.
- CI does not run the core test suite when a service includes it (`core.yml` comment), and does not compare rule sets
  across services.

## Files to create / modify

- Create `platform/contexts/tenancy/src/test`, `.../access/src/test`, `.../integration/src/test` with domain and
  use-case tests using in-memory ports (mirror the billing/tenant patterns): `Role.replacePermissions`,
  `ApiCredential.isUsable/expireAt`, `Hostname`/`TenantCode`, `ExpectedVersion`, rotate overlap, grantability.
- Create `billing/contexts/usage/src/test/.../RecordUsageUseCaseTest.java`;
  `billing/contexts/money/src/test/.../ChargeUseCaseTest.java`, `LedgerPostingTest.java`,
  `ExpireReservationsUseCaseTest.java`; `billing/contexts/payment/src/test/.../PaymentSettlementTest.java`.
- Create `core/core-spring/src/test/.../PermissionInterceptorTest.java`, `SpringUnitOfWorkTest.java`,
  `ScopedTenantContextTest.java`, `UuidV7IdGeneratorTest.java`.
- Create `core/core-messaging/src/test/.../JooqAuditLogIntegrationTest.java`,
  `core/core-suite/src/test/.../SuiteTenantFilterTest.java`, `SuiteEntitlementFilterTest.java`,
  `SuiteActorConverterTest.java`.
- Create `core/core-kernel/src/test/.../CoreKernelBoundaryTest.java`,
  `core/core-spring/src/test/.../CoreSpringBoundaryTest.java` (or add boundary rules to `core-arch`).
- Split `billing/apps/app-boot/src/test/java/dev/ngb/billing/payment/PaymentIntegrationTest.java` into
  `TopUpIntegrationTest`, `StripeEventIntegrationTest`, `RefundIntegrationTest`, `ReconciliationIntegrationTest` with a
  shared `PaymentWorld` fixture.
- Move tenant test classes into mirrored packages (`.../application/user/command/...`, etc.).
- Add `Jooq*ReadRepositoryIntegrationTest` for the five read repositories or soften the doc claim.
- Modify `docs/architecture.md`, `docs/multi-tenancy-and-data-isolation.md`, `docs/authentication-and-authorization.md`,
  `docs/runtime-profiles.md`, `docs/tenant-system.md`, `contracts/messaging/README.md`, `contracts/events/README.md`:
  correct the claims to match the code after phases 1–10; mark `web/` as not implemented in the decision ledger.
- Modify `.github/workflows/core.yml` to run the core tests on a core change (it already does `./gradlew build`);
  verify the comment and add a suite job that fails on an architecture-rule diff between services.
- Add regression tests for every fixed finding that does not already have one (escalation, credential race,
  Keycloak compensation, rate limit, out-of-transaction read, malformed event, replica permissions, envelope
  round-trip, broker claim, snapshot cursor, tenant UoW).

## Tasks

1. Add the missing tests module by module, starting with the security/transaction tests from phases 2–6.
2. Split the payment integration test and keep the same coverage (count `@Test` before/after).
3. Mirror tenant test packages; enable the rule from phase 7.
4. Add the read-repository tests or edit the doc claim.
5. Add core boundary tests and the missing core machinery tests.
6. Correct every drifted document; keep the decision ledger honest.
7. Add a CI job that compares the rule IDs and runs the core tests.
8. Record any test that cannot run without Docker in the plan's verification notes.

## Verification

- `cd core && ./gradlew build`
- `cd platform && ./gradlew build`
- `cd billing && ./gradlew build`
- `cd tenant && ./gradlew checkNoBillingDependency build`
- `docker compose up -d --build --wait --wait-timeout 600 && ops/smoke/isolation.sh && ops/smoke/suite-smoke.sh`
- `grep -rn "each core module\|build rule, not a review promise\|same document as the webhook" docs contracts`
  returns no stale claim.

## Success criteria

- [ ] Every finding in the plan has at least one automated regression test or an explicit "not testable here" note.
- [ ] Platform has domain/use-case unit tests; usage, money and settlement have focused tests.
- [ ] `PaymentIntegrationTest` no longer exceeds ~300 lines; coverage is unchanged.
- [ ] Test packages mirror production and the rule enforces it.
- [ ] Docs and contracts describe the implemented behaviour; the web placeholder claim is removed.
- [ ] CI runs core tests and detects rule-set drift.

## Risks

Docker/Testcontainers may be unavailable. Mitigation: keep unit tests Docker-free, run integration tests where Docker
exists, and report the skipped set instead of claiming a full pass.
