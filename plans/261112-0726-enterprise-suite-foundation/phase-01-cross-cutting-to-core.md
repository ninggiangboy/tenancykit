---
phase: 1
title: "Cross-cutting code to core"
status: pending
priority: P1
effort: "2d"
dependencies: []
---

# Phase 1: Cross-cutting code to core

## Goal

Move the cross-cutting types that every Billing context imports from `billing.platform.api`, together with the outbox and inbox implementations, into `core/`. Behaviour stays identical. Afterwards Platform and the Hub can be built on them.

## Key insights

- `platform.api` is imported by all 8 Billing contexts, but mostly for cross-cutting types: errors (117 imports), audit (about 130), `TenantContext` (49), `TenantSystemId` (33). These are not Platform business logic.
- `Actor`, `Permission` and the permission annotations are **not** moved here. They change shape (string codes) and move in phase 4, when Platform first needs them, and Billing adopts them in phase 8.
- `core-spring` only depends on `spring-tx` today, and `tenant/` consumes it. The outbox needs jOOQ, so it goes into a new module `core/core-messaging`, which `tenant/` does not consume.
- `JooqEventInbox` and `JooqOutboxAppender` use Billing's generated `Tables.EVENT_INBOX` and `Tables.OUTBOX`. In core they use jOOQ's plain-SQL DSL (`DSL.table(DSL.name("outbox"))`) against a documented table contract, because core cannot see an app's generated schema.
- This supersedes the 2026-11-12 decision that outbox and inbox implementations stay in Billing: three systems now need them.

## Files

Move (package `dev.ngb.billing.platform.api.*` → new core package):

| From (`billing/contexts/platform/src/api/java/dev/ngb/billing/platform/api/`) | To |
|---|---|
| `error/{AccessDeniedException,ConflictException,IdempotencyConflictException,NotFoundException}.java` | `core/core-kernel/src/main/java/dev/ngb/core/error/` |
| `audit/{Audit,AuditEntry,AuditLog}.java` | `core/core-kernel/src/main/java/dev/ngb/core/audit/` |
| `idempotency/{Fingerprint,RepeatableCreate}.java` | `core/core-kernel/src/main/java/dev/ngb/core/idempotency/` |
| `tenant/TenantContext.java`, `actor/RequestScope.java` | `core/core-kernel/src/main/java/dev/ngb/core/tenancy/` |
| `tenant/TenantSystemId.java` | `core/core-kernel/src/main/java/dev/ngb/core/id/TenantSystemId.java` |

Move from `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/infrastructure/`:

| From | To |
|---|---|
| `tenancy/ScopedTenantContext.java` | `core/core-spring/src/main/java/dev/ngb/core/spring/tenancy/ScopedTenantContext.java` |
| `messaging/JooqOutboxAppender.java`, `messaging/JooqEventInbox.java`, `messaging/OutboxRelay.java` | `core/core-messaging/src/main/java/dev/ngb/core/messaging/outbox/` (rewritten to plain-SQL table references) |
| `persistence/JooqAuditLog.java` | `core/core-messaging/src/main/java/dev/ngb/core/messaging/audit/JooqAuditLog.java` (the module is the "jOOQ-backed shared infrastructure" module; same plain-SQL rule) |

Create:
- `core/core-messaging/build.gradle.kts` (`api(project(":core-spring"))`, `api("org.jooq:jooq")`, `implementation("tools.jackson.core:jackson-databind")` or the Jackson coordinate Billing uses today, Micrometer core).
- `core/core-messaging/src/main/resources/dev/ngb/core/messaging/schema-contract.md`: the column contract of `outbox`, `event_inbox` and `audit_log` that every consuming app's migration must satisfy.
- `core/core-messaging/src/test/java/.../OutboxRelayIntegrationTest.java` (Testcontainers PostgreSQL, applies a minimal DDL matching the contract).

Modify:
- `core/settings.gradle.kts`: `include("core-kernel", "core-spring", "core-messaging")`.
- `billing/contexts/*/build.gradle.kts`: contexts that only used the moved types depend on core instead of `:contexts:platform` where nothing else is used (keep the platform dependency where `Actor`/`Permission` are still used).
- `billing/apps/app-boot/build.gradle.kts`: depend on `dev.ngb.core:core-messaging`.
- `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/infrastructure/InfrastructureConfiguration.java`: wire the core beans.
- Every import site (mechanical `sed` over `billing/`).
- `billing/apps/app-boot/src/test/java/dev/ngb/billing/architecture/ArchitectureRules.java`: rules that name `platform.api.error|audit|idempotency|tenant` point at `dev.ngb.core.*`.
- Core ArchUnit test (or the existing one in `core/`): `dev.ngb.core..` imports nothing from `dev.ngb.billing..`, `dev.ngb.tenant..`, `dev.ngb.platform..`, `dev.ngb.notification..`.
- `.github/workflows/core.yml`: builds `core-messaging`.

Delete: the moved files from `billing/contexts/platform/src/api/...` and `billing/apps/app-boot/.../infrastructure/{tenancy/ScopedTenantContext,messaging/JooqOutboxAppender,messaging/JooqEventInbox,messaging/OutboxRelay,persistence/JooqAuditLog}.java`.

## Implementation steps

1. Create `core-messaging` with the build file and empty packages; `cd core && ./gradlew build` green.
2. Move the kernel types (`git mv`, then fix the `package` line). Keep Javadoc unchanged.
3. Rewrite every import in `billing/` with one `sed` per package, for example `s/dev\.ngb\.billing\.platform\.api\.error\./dev.ngb.core.error./g`.
4. Move `ScopedTenantContext` into `core-spring`.
5. Move the outbox, inbox and audit implementations into `core-messaging`. Replace generated `Tables.*` references with `DSL.table(DSL.name(...))` and `DSL.field(DSL.name(...), SQLDataType...)` constants in one package-private `MessagingTables` class. Keep SQL semantics identical (`for update skip locked`, `on conflict do nothing`).
6. Keep metric names (`billing.outbox.delivered`, `billing.outbox.dead`) configurable through a prefix constructor argument; Billing passes `billing`.
7. Write the schema contract file and the core integration test.
8. Update ArchUnit rules and the core boundary test.
9. Run all three builds.

## Todo

- [x] `core-messaging` module created and built by `core.yml`
- [x] Error, audit, idempotency, tenancy, `TenantSystemId` types in `core-kernel`
- [x] `ScopedTenantContext` in `core-spring`
- [x] Outbox appender, inbox, relay and audit log in `core-messaging` with plain-SQL table refs
- [x] Billing imports rewritten; moved files deleted
- [x] ArchUnit rules updated; core boundary rule added
- [x] All three builds green

## Verification

- `cd core && ./gradlew build`, `cd billing && ./gradlew build`, `cd tenant && ./gradlew build` all pass.
- `grep -rnE "platform\.api\.(error|audit|idempotency|tenant)\." billing --include='*.java'` returns nothing.
- `RowLevelSecurityIntegrationTest`, the outbox relay tests and `ArchitectureMetaTest` still pass in Billing.

## Risks

- Plain-SQL jOOQ loses compile-time column checks inside core. Mitigation: the core integration test runs against the contract DDL, and each app's existing tests run the real tables.
- `tenant/` must not pick up jOOQ through core. Mitigation: `tenant/` depends only on `core-kernel` and `core-spring`; check with `cd tenant && ./gradlew :apps:app-boot:dependencies | grep core-messaging` (expect nothing).

## Security

No change in behaviour. `audit_log` keeps its `revoke update, delete` grant in each app's migration.
