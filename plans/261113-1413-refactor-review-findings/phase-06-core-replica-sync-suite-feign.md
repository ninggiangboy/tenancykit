---
phase: 6
title: "Core replica, sync, suite and feign"
status: pending
priority: P1
effort: M
dependencies: [1, 5]
---

# Phase 6: Core replica, sync, suite and feign

## Goal

Make the replica writer strict, the snapshot rebuild bounded, the suite filters and actor resolver fail closed with
the right status, the Feign retry method-aware, and the remaining core ports typed.

## Findings addressed

- `ReplicaWriter.strings()` returns an empty array when `permissions` is missing or the wrong type, silently clearing
  a user's rights; every other field throws.
- Stream names are declared four times (`streams`, `eventTypeOf`, `columnsOf`, `ReplicaEventHandler.accepts`,
  `ReplicaTables`, `SnapshotController`).
- `SnapshotClient.fetchAll` accumulates a whole stream in memory; `ReplicaBootstrap.rebuild` opens one transaction per
  stream; a non-advancing cursor loops forever.
- `SuiteActorResolver.java:94,99` calls `UUID.fromString` on token claims without wrapping; a malformed claim becomes
  500 because `SuiteActorConverter` catches only `TenantMismatchException`.
- `SuiteEntitlements.requireEnabled` throws `NotFoundException("app-not-enabled")` and is unused in production;
  `TenantContext` is passed per call and read twice by the filter.
- `SuiteTenantFilter` returns `application/json {"error":"not_found"}` while `SuiteEntitlementFilter` returns
  `application/problem+json`; both duplicate the health-path predicate.
- `StatusFeignErrorMapper` marks every `5xx/429` retryable, including non-idempotent POSTs.
- `TransactionScopeHook` is typed on `Object`; `AesGcmSecretCipher` hard-codes `billing.secrets.*` messages;
  `UuidV7IdGenerator` ignores the injected `Clock`.
- `ReplicaTables`/`ReplicaSchemaAssertions` name Billing inside the shared core; `hasUniqueIndex` actually checks the
  primary key; the replica DDL is copied into four test classes.

## Files to create / modify

- `core/core-replica/.../ReplicaWriter.java`: throw on a wrong-typed `permissions`, keep the documented
  "absent means empty" only if a test requires it; add the negative tests.
- Create `core/core-replica/.../ReplicaStream.java` (enum: stream name, event type, table, columns, conflict key);
  use it in `ReplicaWriter`, `ReplicaEventHandler`, `ReplicaSchemaAssertions`, `SnapshotController`, queue bindings and
  tests.
- `core/core-platform-sync/.../SnapshotClient.java`: stream pages through a consumer, guard against a repeated cursor
  (max pages / no-progress detection).
- `core/core-platform-sync/.../ReplicaBootstrap.java`: apply each page in its own transaction; log per stream; keep
  the version guard.
- `core/core-suite/.../security/SuiteActorResolver.java`: parse claims defensively and return empty / throw a typed
  mismatch; `SuiteActorConverter` maps to 401/403.
- `core/core-suite/.../web/SuiteEntitlements.java`: message, inject `TenantContext`; delete `requireEnabled` if it
  stays unused, or use it in the filter.
- Create `core/core-suite/.../web/SuiteProblems.java`: one problem+json writer and one health predicate used by both
  filters.
- `core/core-feign/.../StatusFeignErrorMapper.java` / `FeignClients.java`: retry only when the method is safe or an
  `Idempotency-Key` is present; document the matrix.
- `core/core-kernel/.../runtime/TransactionScopeHook.java` + `core-spring/.../SpringUnitOfWork.java`: type the scope
  on `RequestScope` (or an opaque `ScopeKey`).
- `core/core-spring/.../security/AesGcmSecretCipher.java`: take the property prefix as a constructor argument.
- `core/core-spring/.../runtime/UuidV7IdGenerator.java`: accept a `Clock`; add a version/variant test.
- `core/core-replica/.../testFixtures/ReplicaSchemaAssertions.java` and the four test classes: execute the shipped
  `replica-tables.sql`; rename `hasUniqueIndex` to `hasPrimaryKey`; remove Billing from the javadoc.
- `core/core-replica/src/main/resources/.../replica-tables.sql` remains the single DDL source.

## Tasks

1. Add a failing test for a `user-access.changed` payload without `permissions`.
2. Introduce `ReplicaStream` and replace the four switch statements and the queue-binding list.
3. Make the snapshot client page into a consumer and fail fast on a stuck cursor; apply pages transactionally.
4. Make malformed token claims produce 401/403, never 500; add tests.
5. Unify the filter error body and health bypass.
6. Make Feign retry method-aware; add a POST 500 test that asserts no replay.
7. Type `TransactionScopeHook`; update the two hooks.
8. Parameterize the cipher prefix; inject `Clock` into the id generator.
9. Replace the copied test DDL with the shipped script.

## Verification

- `cd core && ./gradlew build`
- `cd core && ./gradlew :core-replica:test :core-platform-sync:test :core-suite:test :core-feign:test :core-spring:test`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*Replica*IntegrationTest' --tests '*ArchitectureTest'`
- `cd platform && ./gradlew :apps:app-boot:test --tests '*Snapshot*' --tests '*Replica*'`

## Success criteria

- [ ] A missing/wrong-typed `permissions` rejects the event and never clears grants.
- [ ] One enum is the only list of replica streams; adding a stream changes one file plus a migration.
- [ ] A rebuild applies page by page and uses bounded memory; a stuck cursor fails instead of looping.
- [ ] A token with a malformed `tenant_id`/subject returns 401/403.
- [ ] A POST 5xx is not retried without an idempotency key.
- [ ] Core contains no service name in code or javadoc.

## Risks

The `ReplicaStream` refactor and the envelope change from phase 5 intersect. Mitigation: complete phase 5 first and
keep the stream enum independent of the wire field names.
