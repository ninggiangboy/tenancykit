---
phase: 2
title: "Service Skeleton and Delivery Domain"
status: pending
priority: P1
effort: "M"
dependencies: [1]
---

# Phase 2: Service Skeleton and Delivery Domain

## Goal

Stand up `mailer/` as its own Gradle build modelled on `tenant/`, stripped of everything a consumer-only
service does not use, with the delivery domain model in place and a green build that includes ArchUnit and
Modulith.

## Key Insights

- `tenant/` is the correct starting point, not Billing: Billing carries `core-replica`,
  `core-platform-sync`, `core-suite`, a tenant-scope hook and 34 migrations. But the tenant is *not* a
  verbatim template — it is a web service with HTTP, Spring Security, a Feign client and a test harness for
  both. Mailer serves no HTTP business endpoint, so roughly half of the copied app shell must be deleted
  rather than carried.
- The service name is `mailer`, not `notification`. The 2026-11-12 cleanup left `docs/architecture.md:156`
  claiming an ArchUnit rule rejects `dev.ngb.notification`; **no such rule exists in code** (a repository grep
  finds `notification` only in prose). Reusing that name would revive a cancelled identity on top of a
  document that is already wrong.
- `tenant/build.gradle.kts:11` forbids `core-messaging`, `core-replica`, `core-platform-sync` and
  `core-suite`. Mailer **needs** `core-messaging`, so its gate keeps that axis but drops `core-messaging`
  from the forbidden set: `{core-replica, core-platform-sync, core-suite}`.
- **The copied gate cannot catch a foreign service on its own.** Both existing gates rely on
  `ProjectDependency` matching, which can only fire inside one build; a sibling build is reached as an
  external coordinate and is caught by the dependency *group*. The tenant's gate has no group branch at all
  (`tenant/build.gradle.kts:25`), and Billing's group branch names `dev.ngb.platform`/`dev.ngb.tenant`
  (`billing/build.gradle.kts:24`). Mailer's gate must therefore include both the module axis and a group
  axis, or phase 3's deliberate-failure proof cannot fail.
- **Do not copy the tenant's `ArchitectureTest` verbatim.** It asserts
  `production.contain("dev.ngb.tenant.identity.domain.user.User")` and
  `assertThat(useCases).isGreaterThanOrEqualTo(10)`
  (`tenant/apps/app-boot/src/test/java/dev/ngb/tenant/architecture/ArchitectureTest.java:39,51`). Mailer has
  two use cases, so the build fails on the first run. Worse, the tenant's set has **no rule for `@Scheduled`
  or `@Profile`**; those live in `billing/apps/app-boot/src/test/java/dev/ngb/billing/architecture/ArchitectureTest.java:131,143`
  and mailer, being the only other build with two runtimes, needs them.
- Two `@Configuration` classes declaring `clock()` and `idGenerator()` fail at startup with
  `BeanDefinitionOverrideException`. There must be exactly one wiring class; phase 6 owns it, so this phase
  creates none.
- `layered-module-conventions.gradle.kts` creates an `api` source set for every module, but `api` exists for
  read models and interfaces *other modules* bind to (`docs/architecture.md:206`). Mailer has one context and
  no cross-context caller, so the set stays empty; the decoded command record belongs in an `application`
  role package.
- The convention plugin hardcodes `project(":libs:shared-core")`. Rather than ship an empty module, put the
  two value objects that every layer may name (`Recipient`, `FromKey`) there, which is exactly what the
  tenant does with its own `shared-core`.

## Files to Create / Modify

Create the tree by copying `tenant/`, then deleting `modules/` and the app shell listed under "Do not copy".

- `mailer/settings.gradle.kts` — `includeBuild("../core")`; `libs:shared-core`, `libs:jooq-schema`,
  `contexts:email`, `apps:app-boot`, `testing:arch-fixtures`
- `mailer/build.gradle.kts` — isolation gate with both axes:
  `foreignServices = setOf("billing", "platform", "tenant")`,
  `foreignGroups = setOf("dev.ngb.billing", "dev.ngb.platform", "dev.ngb.tenant")`,
  `serviceOnlyCoreModules = setOf("core-replica", "core-platform-sync", "core-suite")`
- `mailer/gradle/libs.versions.toml`, `mailer/gradle/wrapper/*`, `mailer/gradlew`, `mailer/gradlew.bat`
- `mailer/build-logic/` — `rootProject.name = "mailer-build-logic"`, the three convention plugins unchanged
  except `boot-conventions.gradle.kts:6` → `mainClass = "dev.ngb.mailer.Application"`
- `mailer/libs/shared-core/build.gradle.kts` — `api("dev.ngb:core-kernel")`
- `mailer/libs/shared-core/src/main/java/dev/ngb/mailer/shared/Recipient.java` — validated address value object
- `mailer/libs/shared-core/src/main/java/dev/ngb/mailer/shared/FromKey.java` — validated key value object
- `mailer/libs/jooq-schema/build.gradle.kts`
- `mailer/contexts/email/build.gradle.kts` — `id("layered-module-conventions")`
- `mailer/apps/app-boot/build.gradle.kts` — `dev.ngb:core-messaging`, `dev.ngb:core-spring`,
  `spring-boot-starter`, `spring-boot-starter-jooq`, `spring-boot-starter-amqp`, `spring-boot-starter-actuator`,
  `spring-boot-starter-flyway`, `json-schema-validator`, `dev.ngb:core-arch` on the test classpath
- `mailer/apps/app-boot/src/main/java/dev/ngb/mailer/Application.java`
- `mailer/testing/arch-fixtures/` — copy, repoint the foreign-service fixture
- `mailer/README.md`

Domain and application classes (this phase):

- `mailer/contexts/email/src/domain/.../email/domain/delivery/DeliveryStatus.java` — `PENDING`, `IN_PROGRESS`, `SUCCEEDED`, `DEAD`
- `.../domain/delivery/EmailDelivery.java` — aggregate: id, tenantId, requestId, recipient, fromKey, subject, status, attempts, nextAttemptAt, claimedUntil, providerMessageId, lastError, createdAt, completedAt
- `.../domain/delivery/DeliveryAttempt.java`
- `.../domain/delivery/DeliveryPolicy.java` — `maxAttempts`, `dev.ngb.core.util.Backoff`, `lease`, `exhausted`, `retryDelay`; construct from the same field vocabulary as `core/core-messaging/src/main/java/dev/ngb/core/messaging/config/MessagingProperties.java:52-72` (`Relay`), so the delivery tuning does not invent a fifth config shape
- `mailer/contexts/email/src/application/.../email/application/email/command/EmailSendCommand.java` — decoded record, **not** in `api`
- `.../application/email/command/AcceptEmailCommandUseCase.java` — depends on `UnitOfWork`
- `.../application/email/command/DeliverEmailsUseCase.java` — depends on `UnitOfWork`
- `.../application/email/port/EmailSender.java` — `SendOutcome send(OutgoingEmail email)`
- `.../application/email/port/SendOutcome.java` — sealed: `Accepted(String messageId)`, `Rejected(String reason)`, `Transient(String reason)`
- `.../application/email/port/OutgoingEmail.java`

## Architecture

`EmailSender` is the seam and must not leak a transport's vocabulary. Its result is a three-way closed set
because that is exactly the decision the ledger needs:

- `Accepted` → `SUCCEEDED`, record the provider message id.
- `Rejected` → `DEAD` immediately. The request itself is wrong and retrying only burns quota.
- `Transient` → `PENDING` with backoff, until the policy is exhausted.

Everything that is not a send — dedupe, ledger writes, backoff, lease — stays in the application layer, so
both adapters inherit it unchanged.

`DeliveryPolicy` is a domain object rather than a reuse of `MessagingProperties.Relay`, because the email
channel has a third outcome (`Rejected`) that maps to an immediate terminal state, which the relay's
publish-or-fail model does not have. It copies `Relay`'s *field vocabulary* and its jittered backoff so the
two do not drift conceptually, without coupling the job to the broker's configuration record.

## Implementation Steps

1. Copy `tenant/` to `mailer/`; delete `modules/`, `boot/config/*` tenant configurations, `boot/security/`,
   `boot/web/`, `boot/bootstrap/`, and the tenant's `application.yml` keys beyond datasource and flyway.
2. Delete from `mailer/apps/app-boot/build.gradle.kts`: `spring-boot-starter-webmvc`,
   `spring-boot-starter-security`, `spring-boot-starter-webmvc-test`, `dev.ngb:core-feign`.
   Delete `support/HttpIntegrationTest.java`, `support/HttpWorld.java`, `support/FakeBilling*.java` and
   `boot/ApplicationContextTest.java`. Keep `PostgresContainerConfig.java` for phase 4.
3. Rewrite `settings.gradle.kts`, `build.gradle.kts` (gate with both axes) and the three convention plugins.
4. Create `libs/shared-core` with `Recipient` and `FromKey`; both reject CR, LF and the characters that break
   a header.
5. Create `contexts/email` with package root `dev.ngb.mailer.email`; write the domain classes and the ports.
6. Write the two use case shells. Each takes `UnitOfWork` and delegates; no adapter code.
7. Build the architecture test set from **Billing's**, not the tenant's: keep the generic rules and the two
   runtime rules (`everyScheduledClassRunsInTheWorkerRuntime`,
   `everyClassInTheJobsPackageRunsInTheWorkerRuntime`), set `ROOT = "dev.ngb.mailer"`,
   `jooq = "dev.ngb.mailer_jooq"`, `otherServiceRoots = {dev.ngb.billing.., dev.ngb.platform.., dev.ngb.tenant..}`,
   and set the non-vacuity assertions to mailer's real values:
   `production.contain("dev.ngb.mailer.email.domain.delivery.EmailDelivery")` and
   `assertThat(useCases).isGreaterThanOrEqualTo(2)`. State the numbers explicitly so a later refactor that
   deletes a use case fails loudly.
8. Set `tableOwner` to an empty map; phase 4 fills it.

## Todo

- [ ] `mailer/` copied from `tenant/`, `modules/` and the web/security/test shell deleted
- [ ] `settings.gradle.kts` + `build.gradle.kts` gate with both the project and the group axis
- [ ] `build-logic` three plugins, `mainClass = dev.ngb.mailer.Application`
- [ ] `libs/shared-core` holds `Recipient` and `FromKey` with header-injection validation
- [ ] Domain: `DeliveryStatus`, `EmailDelivery`, `DeliveryAttempt`, `DeliveryPolicy`
- [ ] Ports: `EmailSender`, `SendOutcome`, `OutgoingEmail`; two use case shells depending on `UnitOfWork`
- [ ] Architecture tests rebuilt from Billing's set, including the two runtime rules
- [ ] `architecture` non-vacuity assertions set to mailer's real values
- [ ] Deliberate-failure proof of the gate recorded

## Verification

```bash
cd mailer && ./gradlew :apps:app-boot:build
cd mailer && ./gradlew :apps:app-boot:test --tests '*ArchitectureTest' --tests '*ArchitectureMetaTest'
```

Both must pass. Then prove the gate, using a dependency it actually checks:

```bash
# temporarily add to mailer/apps/app-boot/build.gradle.kts:
#   implementation("dev.ngb:core-replica")
cd mailer && ./gradlew build      # MUST fail naming core-replica
# revert
```

If that build stays green, the gate is decorative and must be fixed before phase 3.

## Risk Assessment

| Risk | Signal it broke | Response |
|---|---|---|
| Tenant's `ArchitectureTest` copied verbatim | First run fails on `identity.domain.user.User` and on `>= 10` use cases | Rebuild from Billing's set with mailer's real counts |
| Web/security scaffolding left in | Worker's `/actuator/health` answers 401 and compose never reports healthy | Delete the starters and any copied security configuration |
| Two wiring classes declaring `clock`/`idGenerator` | `BeanDefinitionOverrideException` at startup once phase 6 lands | No `SystemConfiguration` in this phase; phase 6 owns the only one |
| Gate built from the tenant's shape | It can never fail on an external coordinate | Add the group axis and keep the module axis without `core-messaging` |
| `EmailSendCommand` in `api` | `api-exposes-no-internals` and a contract nobody binds to | Put it in `application/.../command` |

## Security Considerations

- The isolation gate is the suite's coupling boundary; a decorative gate is worse than none because it
  creates false confidence.
- `Recipient` and `FromKey` validate at construction, so no later layer can be handed a string that breaks a
  mail header.
- No credentials in this phase.

## Next Steps

Phase 3 registers `mailer` in the repository-wide lists. Phase 4 needs the `PostgresContainerConfig` kept
here.
