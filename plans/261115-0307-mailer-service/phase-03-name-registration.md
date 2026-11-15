---
phase: 3
title: "Name Registration and Architecture Gates"
status: pending
priority: P1
effort: "S"
dependencies: [2]
---

# Phase 3: Name Registration and Architecture Gates

## Goal

Teach every isolation gate in the repository that `mailer` exists, and correct the one document that claims a
rule which does not exist.

## Key Insights

There is no service registry in `core`; the list is hardcoded. Verified locations:

| What | Where | What actually happens |
|---|---|---|
| Gradle isolation task | `billing/build.gradle.kts:10`, `platform/build.gradle.kts:10`, `tenant/build.gradle.kts:10` | `ProjectDependency` path match plus a dependency **group** match (`billing/build.gradle.kts:21,24`) |
| ArchUnit cross-service rule | `otherServiceRoots()` in each service's `.../architecture/ArchitectureRules.java` (billing `:100`, platform `:63`, tenant `:90`) | this one does work and does catch package references |
| Package boundary tests in core | `CoreKernelBoundaryTest.java:35`, `CoreSpringBoundaryTest.java:35`, `CoreBoundaryTest.java:17`, `ReplicaBoundaryTest.java:21,28`, `PlatformSyncBoundaryTest.java:20,27`, `SuiteBoundaryTest.java:20,27`, `FeignBoundaryTest.java:20` | hardcoded package lists |
| CI | `.github/workflows/core.yml:52-54` | runs each service's architecture tests |

Two corrections to the naive plan:

- **Adding `"mailer"` to `foreignServices` alone does nothing.** That set is matched against
  `ProjectDependency.path` segments, which can only exist inside one Gradle build. A sibling build appears as
  an external coordinate with a group. So each service's gate needs `mailer` on its group axis too; without
  that, the registration is a no-op. Mailer's own gate already has both axes from phase 2.
- **`ArchitectureRulesTest` is not a service registry.** `core/core-arch/src/test/java/dev/ngb/core/arch/ArchitectureRulesTest.java:58-66`
  builds three profiles and asserts that the same pure function returns the same rule ids for each — it
  cannot detect a missing service and adding mailer to it changes nothing. Do not edit it; the real guard is
  each service's own `ArchitectureMetaTest`.
- `docs/architecture.md:156` asserts an ArchUnit rule forbids `dev.ngb.notification`. No such rule exists in
  Java; the string appears only in prose. This phase fixes the document rather than "noting" a live rule.

## Files to Create / Modify

- Modify: `billing/build.gradle.kts` — group set at `:24` gains `"dev.ngb.mailer"`
- Modify: `platform/build.gradle.kts` — group set gains `"dev.ngb.mailer"`
- Modify: `tenant/build.gradle.kts` — file has **no** group branch (`:25` is the module branch); add one, or
  the tenant gate cannot see an external `dev.ngb.mailer` coordinate
- Modify: `billing/apps/app-boot/src/test/java/dev/ngb/billing/architecture/ArchitectureRules.java` — `"dev.ngb.mailer.."`
- Modify: `platform/apps/app-boot/src/test/java/dev/ngb/platform/architecture/ArchitectureRules.java` — same
- Modify: `tenant/apps/app-boot/src/test/java/dev/ngb/tenant/architecture/ArchitectureRules.java` — same
- Modify: the seven core boundary tests — `"dev.ngb.mailer.."`, and `"dev.ngb.mailer_jooq.."` where the jooq list appears
- Modify: `.github/workflows/core.yml:52-54` — a `Mailer rule ids` step with `working-directory: mailer`
- Modify: `docs/architecture.md:155-156` — replace the false claim with the truth: the bans are the Gradle
  gate and each service's `otherServiceRoots`; `mailer` is the only service permitted to call an AWS API

## Implementation Steps

1. Add `"mailer"` to `foreignServices` in all three services (this covers an accidental project dependency).
2. Add `"dev.ngb.mailer"` to Billing's and Platform's dependency-group set, and add the missing group branch
   to the tenant's gate.
3. Add `"dev.ngb.mailer.."` to the three `otherServiceRoots()` sets. This is the check that genuinely fires.
4. Add `"dev.ngb.mailer.."` to the seven core boundary tests, plus `"dev.ngb.mailer_jooq.."` where a jooq
   pattern list exists.
5. Add the CI step for mailer's architecture tests.
6. Fix `docs/architecture.md:155-156`.

## Todo

- [ ] `"mailer"` in the three `foreignServices` sets
- [ ] `"dev.ngb.mailer"` in Billing's and Platform's group sets; group branch added to the tenant's gate
- [ ] `"dev.ngb.mailer.."` in the three `otherServiceRoots()`
- [ ] `"dev.ngb.mailer.."` in the seven core boundary tests, plus `mailer_jooq` where applicable
- [ ] CI step for mailer's architecture tests
- [ ] `docs/architecture.md:155-156` false claim replaced
- [ ] ArchUnit deliberate-failure proof recorded

## Verification

```bash
cd billing  && ./gradlew checkNoForeignServiceDependency
cd platform && ./gradlew checkNoForeignServiceDependency
cd tenant   && ./gradlew checkNoBillingDependency
cd core     && ./gradlew build
cd mailer   && ./gradlew build
```

Then prove the ArchUnit half, which is the half that actually matters. In a scratch branch, add a class to
mailer that references `dev.ngb.billing.customer.domain.customer.Customer` and confirm
`cd mailer && ./gradlew :apps:app-boot:test --tests '*ArchitectureTest'` fails with
`no-dependency-on-another-service`. Revert afterwards. Record the failure output in the PR: a rule that has
never failed has not been tested.

## Risk Assessment

| Risk | Signal it broke | Response |
|---|---|---|
| Only `foreignServices` edited | Nothing ever fails; the gate looks configured | The group axis is the one that can fire across builds |
| Tenant gate left without a group branch | `cd tenant && ./gradlew checkNoBillingDependency` passes but would not catch mailer | Add the branch; the tenant's `:25` only matches by module name |
| One core boundary test missed | `core` build passes while the rule no longer covers mailer | `grep -rn "dev.ngb.tenant\.\." core/` and add mailer beside every hit |
| Editing `ArchitectureRulesTest` and calling it done | No behaviour change, false confidence | It is a tautology; each service's `ArchitectureMetaTest` is the real guard |

## Security Considerations

These gates are the tenant-isolation and coupling boundary. Registering the new name is a security task:
an unregistered service can silently read another service's classes, and an unregistered *archived* claim
misleads the next reader.
