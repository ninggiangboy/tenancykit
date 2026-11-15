---
title: "Phase 2: Build Skeleton and Architecture Gates"
status: todo
---

# Phase 2: Build Skeleton and Architecture Gates

## Context Links

- `mailer/settings.gradle.kts`, `mailer/build.gradle.kts` — the build and the isolation gate to copy
- `mailer/build-logic/src/main/kotlin/{java-conventions,layered-module-conventions,boot-conventions}.gradle.kts`
- `mailer/gradle/libs.versions.toml`
- `mailer/apps/app-boot/src/main/resources/db/migration/V1__baseline.sql` — role and RLS helpers
- `core/core-replica/src/main/resources/dev/ngb/core/replica/replica-tables.sql` — the replica DDL to inline
- `mailer/apps/app-boot/src/test/java/dev/ngb/mailer/architecture/*`, `support/*`
- `billing/apps/app-boot/src/test/java/dev/ngb/billing/architecture/ArchitectureRules.java:37-70` — the `TABLE_OWNER` split

## Overview

Priority P1, size L. Creates `notification/` as a buildable service whose only green thing is its gates: four source
layers per module, an isolation gate that refuses sibling-service code, ArchUnit with a meta test that proves the rules
bite, a Spring Modulith check, a jOOQ drift test, and a boot application that starts with no profile and connects to
nothing.

Doing this before any feature is what makes the later phases inherit the guarantees instead of adding them afterwards.
A service that grows for six phases and only then meets its architecture test spends that phase arguing with the rules.

## Key Insights

- `boot-conventions.gradle.kts` hardcodes `dev.ngb.mailer.Application`, so a copy **must** change the main class. That
  one line is the most likely omission, and a wrong main class produces an image that exits immediately.
- Mailer's isolation gate lists `core-replica`, `core-platform-sync` and `core-suite` as service-only modules because
  mailer keeps no replica and serves no HTTP. Notification needs all three, so here `serviceOnlyCoreModules` is
  **empty** and the gate rests on the foreign groups. Carrying mailer's set over fails on the first legitimate
  dependency.
- `ArchitectureTest` guards against vacuity by asserting a known production class is present. An empty skeleton fails
  that guard, so one real class must exist on day one; the `NotificationType` enum is the cheapest honest subject.
- `query-side-uses-no-domain-aggregates` fails when its `that()` clause matches nothing. Until Phase 7 there is no read
  side, so the rule is named individually in the empty-subject escape hatch with a `// Phase 7 removes this` comment.
- The replica DDL is a resource inside `core-replica`, not a Flyway migration, and Flyway ignores a non-versioned name.
  It is inlined in `V1__baseline.sql` exactly as Billing did, with a comment naming the source. That is a deliberate
  copy with a drift risk, recorded below.
- jOOQ generation is a **test** in mailer (`JooqSchemaTest` with `-Djooq.update=true`), not a Gradle plugin, and the
  generated package is committed. Notification mirrors it with `dev.ngb.notification_jooq`.
- Two contexts, not one: `platform` (replica tables only) and `dispatch` (everything else). This is not stylistic —
  `context-uses-only-own-tables` allows one context to own both, but then the replica has no seam of its own and
  `dispatch` would read another concern's tables by definition.

## Requirements

Functional

- `notification/` builds with `./gradlew build` and fails on any foreign-service dependency.
- The boot application starts with no profile, binds the datasource and serves nothing yet.
- `V1__baseline.sql` creates `notification_app`, the RLS helpers, the five replica tables, `event_inbox` and
  `replica_state`.
- jOOQ classes for those tables are generated and committed.

Non-functional

- Java 25, `-Xlint:all -Werror`, one BOM authority, no version literal outside `gradle/libs.versions.toml`.
- Inner layers (`domain`, `application`) cannot see Spring: the adapter-only BOM makes it a compile error.
- The Dockerfile's build context is the repository root and it copies `notification/`, `core/` and `contracts/`.

## Architecture

```
notification/
├── build-logic/                     three convention plugins
├── gradle/libs.versions.toml
├── libs/shared-core                 dev.ngb.notification.shared
├── libs/jooq-schema                 dev.ngb.notification_jooq (generated, committed)
├── contexts/platform                replica tables + read ports (api is the seam)
├── contexts/dispatch                catalog, templates, in-app, dispatch, email publisher
├── apps/app-boot                    wiring, migrations, jobs, security
└── testing/arch-fixtures            classes the rules are proved against
```

`TABLE_OWNER`, initial state:

| Tables | Owner |
|---|---|
| `Tenants`, `TenantDomains`, `TenantSystems`, `UserGrants`, `AppEntitlements` | `platform` |
| `EventInbox`, `ReplicaState` | `boot` |
| later: `NotificationTemplates`, `NotificationSettings`, `NotificationPreferences`, `Notifications`, `NotificationDispatches` | `dispatch` |
| later: `Outbox` | `boot` |

## Related Code Files

Create

- `notification/settings.gradle.kts` — `rootProject.name = "notification"`, `includeBuild("build-logic")`,
  `includeBuild("../core")`, `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, includes the six projects
- `notification/build.gradle.kts` — isolation gate: foreign services `billing, mailer, platform, tenant`, groups
  `dev.ngb.billing, dev.ngb.mailer, dev.ngb.platform, dev.ngb.tenant`, `serviceOnlyCoreModules = emptySet()`, wired
  into `check`
- `notification/build-logic/settings.gradle.kts`, `build.gradle.kts`,
  `src/main/kotlin/java-conventions.gradle.kts`, `layered-module-conventions.gradle.kts`,
  `boot-conventions.gradle.kts` (`mainClass = "dev.ngb.notification.Application"`)
- `notification/gradle/libs.versions.toml` — mailer's, minus AWS/SES/STS, plus web, security, resource-server and
  `jmustache`
- `notification/gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- `notification/libs/shared-core/build.gradle.kts` + `.../shared/TemplateKey.java`
- `notification/libs/jooq-schema/build.gradle.kts`
- `notification/contexts/platform/build.gradle.kts` +
  `.../platform/api/{package-info.java,TenantDirectory.java}` (the port is empty; the `package-info` carries
  `@NamedInterface("api")`)
- `notification/contexts/dispatch/build.gradle.kts` +
  `.../dispatch/domain/notification/NotificationType.java` (five values, no behavior yet)
- `notification/apps/app-boot/build.gradle.kts`, `Dockerfile`
- `notification/apps/app-boot/src/main/java/dev/ngb/notification/Application.java`
- `notification/apps/app-boot/src/main/resources/application.yml`, `application-api.yml`, `application-worker.yml`,
  `application-migrate.yml`
- `notification/apps/app-boot/src/main/resources/db/migration/V1__baseline.sql`
- `notification/testing/arch-fixtures/build.gradle.kts` + the fixture tree copied from `mailer/testing/arch-fixtures`
- four test classes under `.../src/test/java/dev/ngb/notification/architecture/` and
  `boot/persistence/JooqSchemaTest.java`, `support/{PostgresContainerConfig,RabbitContainerConfig,RepositoryPaths}.java`

Modify

- none outside `notification/`

## Implementation Steps

1. Copy `mailer/settings.gradle.kts`; change the root name and the included projects.
2. Copy the three convention plugins and change the main class in `boot-conventions`. Confirm the layered plugin
   creates `api`, `domain`, `application`, `adapter` and gives only `adapter` the Spring BOM.
3. Copy the version catalog; remove `aws-*`; add `spring-boot-starter-web`, `spring-boot-starter-security`,
   `spring-boot-starter-oauth2-resource-server`, `jmustache:1.16`. Keep `json-schema-validator`: Phase 6 validates its
   own `email.send` payload against the contract before publishing it.
4. Write `notification/build.gradle.kts` with the gate, keeping the header comment that explains why
   `serviceOnlyCoreModules` is empty here while mailer's was not.
5. Create `libs:shared-core` with `TemplateKey` only (`^[a-z0-9][a-z0-9._-]*$`, max 128).
6. Create `contexts:platform` (empty `api`, plus `platform` internal package) and `contexts:dispatch` with
   `NotificationType` (`ACCESS_GRANTED`, `ACCESS_CHANGED`, `ACCESS_DISABLED`, `ENTITLEMENT_CHANGED`,
   `TENANT_DEACTIVATED`) and its `templateKey()` mapping. This enum is the vacuity subject.
7. Write `V1__baseline.sql`: the `notification_app` role guarded by `pg_roles`, schema grants and default privileges,
   `app_tenant_id()`, `enable_tenant_rls(regclass)`, the five replica tables inlined with a comment naming the
   `core-replica` source, then `event_inbox` **without** a foreign key — `tenant_id uuid not null, consumer text not
   null, event_id uuid not null, processed_at timestamptz not null default now(), primary key (tenant_id, consumer,
   event_id)` — plus `event_inbox_processed_idx on event_inbox (processed_at)` and `call enable_tenant_rls('event_inbox')`,
   then `replica_state (id smallint primary key default 1, rebuilt_at timestamptz not null, check (id = 1))`, then the
   platform pseudo-tenant row, copied from Billing's `V3__platform_tenant.sql`, because `outbox.tenant_id` will reference
   `tenants` and the snapshot stream does not reliably deliver that row on a fresh database.
   The inbox's missing FK is not an omission: Billing shipped it and removed it in
   `V25__inbox_without_tenant_fk.sql`, because the first `tenant.changed` for a brand-new tenant could not be recorded and
   that tenant never reached the replica.
8. Write the four profiles. Base: datasource, flyway with `placeholders.appDbPassword`, broker, `server.port`,
   actuator, `suite.app-code: notification`, `suite.replica.*`, and a `notification.*` tree carrying
   `messaging.exchange: platform.events`, `messaging.queue: notification.platform-events`,
   `commands.exchange: mailer.commands`, `commands.routing-key: email.send`. `api`: web on, flyway off, graceful
   shutdown, readiness group `readinessState, db`. `worker`: web on for the actuator probe, flyway off, scheduling pool.
   `migrate`: no web, flyway on, everything else off.
9. Copy the four support classes and four architecture test classes. In `ArchitectureRules` set `ROOT` to
   `dev.ngb.notification`, contexts `platform`/`dispatch`, shared `shared`, jOOQ `dev.ngb.notification_jooq`, the
   `TABLE_OWNER` table above, foreign packages `dev.ngb.billing.., dev.ngb.mailer.., dev.ngb.platform..,
   dev.ngb.tenant..`, and fill **all eleven** `Profile` components the way mailer's real profile does — the constructor
   also takes `persistenceVisibleIn`, `persistencePackages`, `unitOfWorkAllowlist` and `writeSideReadAllowlist`, and a
   seven-argument copy does not compile. The empty-subject escape hatch is **not** in `ArchitectureRules`: it is
   `RULES_WITHOUT_SUBJECTS` in `ArchitectureTest`, where mailer keeps it; name
   `query-side-uses-no-domain-aggregates` there with a `// Phase 7 removes this` comment.
10. Add `contexts/platform/src/api/java/dev/ngb/notification/platform/api/package-info.java` with
   `@org.springframework.modulith.NamedInterface("api")`, exactly as Billing's context api packages do; without it
   `modules.verify()` fails as soon as `dispatch` imports a `platform` api type.
11. Copy `JooqSchemaTest` with package `dev.ngb.notification_jooq`, the same forced type mapping and the same
   `withRoutines(false)` + `withInputSchema("public")` settings — the RLS helper function and procedure must not become
   generated code; run it with `-Djooq.update=true` to commit the schema.
12. Copy the Dockerfile, changing the copied directory and the bootJar path, and keep `contracts/` in the build stage.
13. Prove the gate bites: add `implementation("dev.ngb:core-replica")` to `apps/app-boot`, run
    `./gradlew checkNoForeignServiceDependency`, confirm `BUILD FAILED` naming it, then revert.

## Todo

- [x] Build files, convention plugins, version catalog
- [x] `libs:shared-core` and the `NotificationType` enum
- [x] `contexts:platform` and `contexts:dispatch` skeletons
- [x] `V1__baseline.sql`: roles, helpers, replica tables, inbox **without** a tenant FK, processed index, RLS, replica
      state, platform pseudo-tenant row
- [x] jOOQ schema generated and committed; drift test green
- [x] Architecture, meta and modulith tests copied and green
- [x] Four profiles and the Dockerfile
- [x] Isolation gate proved to fail on a probe dependency, then reverted

## Success Criteria

- `cd notification && ./gradlew build` passes and the report lists the architecture, meta, modulith and jOOQ tests.
- `cd notification && ./gradlew checkNoForeignServiceDependency` passes, and the probe step fails naming the
  dependency.
- `SPRING_PROFILES_ACTIVE=migrate ./gradlew :apps:app-boot:bootRun` against a local PostgreSQL exits 0, and `\dt`
  shows the replica tables, `event_inbox` and `replica_state`.
- `git ls-files notification/libs/jooq-schema | wc -l` is greater than zero.
- `docker build -f notification/apps/app-boot/Dockerfile .` produces a jar containing `commands/` and `permissions/`.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Inlined replica DDL drifts from `core-replica`'s script | Comment names the source; Phase 3's snapshot test fails if a column the writer upserts is missing; a new core column needs a new migration here |
| `boot-conventions` copied with mailer's main class | Success criterion builds and starts the app; the failure is immediate and unambiguous |
| Copied architecture tests carry mailer's subject counts | Subject-count assertions are set to notification's real numbers; `ArchitectureMetaTest` still proves each rule fires on a fixture |
| The empty-subject escape hatch hides a real violation later | It is a named rule in `ArchitectureTest`, not a global switch, and Phase 7 removes it |
| Two contexts cost a `@NamedInterface` and a Modulith module pair that one context would not need | Kept deliberately: `platform` must own the replica tables so `dispatch` cannot read them directly, and the split matches Billing, where the same seam already exists. The cost is one `package-info` and three `*Service`/`Jooq*Repository` pairs |
| The gate is misconfigured and silently never fails | The probe step is a required checklist item |

## Security Considerations

- The application role is created `nosuperuser ... nobypassrls`; a superuser bypasses RLS and makes the isolation claim
  untestable.
- The migration role owns the schema and the app role holds only default privileges, so a missing grant is an error
  rather than a silent widening.
- No secret is committed; the role password is a Flyway placeholder sourced from the environment.
- `-Werror` stays on, so a deprecation that later becomes a removal fails now rather than at a deployment.

## Next Steps

Phase 3 makes the replica live. It depends on this phase's `platform` context, the `event_inbox` table, and the boot
wiring this phase intentionally leaves empty.
