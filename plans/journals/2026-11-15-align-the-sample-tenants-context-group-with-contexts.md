---
title: "Align the sample tenant's context group with contexts"
date: 2026-11-15
summary: tenant/modules becomes tenant/contexts so all three services name the group the same way
---

# Align the sample tenant's context group with contexts

## What changed

`tenant/modules/` is now `tenant/contexts/`. The eight contexts keep their names
(`identity`, `customer`, `catalog`, `resource`, `execution`, `usage`,
`billing-client`, `webhook`), and every Gradle project path follows the directory:
`:modules:money`-style references became `:contexts:...` (31 in `*.kts`, 8 of them
in `settings.gradle.kts` and 8 in `apps/app-boot`). No Java package, source set,
architecture rule or schema changed.

Docs moved with the directory: the `README.md` group table now says the group is
`contexts/` in all three services instead of noting the sample's `modules/` as an
exception, and the four `tenant/modules/...` paths in `docs/architecture.md`
(the DDD-base-types row and the two decision bullets) now read
`tenant/contexts/...`.

## Why

The difference was never a design decision. `billing/regroup-to-contexts` landed on
2026-11-11 and the tenant build was scaffolded on 2026-11-12 with `modules/`
already in its plan text, so the sample copied a name one day after the sibling
build stopped using it. Nothing enforced either name: Modulith and ArchUnit key
off the root package `dev.ngb.tenant`, and `settings.gradle.kts` only lists paths,
so the two names were interchangeable in behaviour and only differed in what the
reference implementation taught about the convention.

The rename was kept in one change set because a commit that moves the directory
without updating `settings.gradle.kts` leaves a red build in the middle of history.

## Evidence

- `./gradlew clean build` in `tenant/`: BUILD SUCCESSFUL, 418 tests, 0 failures,
  0 errors, 0 skipped. The task log names `:contexts:identity`, `:contexts:usage`
  and so on, so the rename is visible in the graph Gradle resolved.
- No test count baseline was taken separately: `git diff -- '*.java'` is empty, so
  the test inventory is identical by construction rather than by measurement.
- `grep -rn ':modules:' tenant --include=*.kts` returns 0.
- `grep -rn 'tenant/modules' --include=*.md . --exclude-dir=plans` returns 0.
- `git status --short` reports 205 `R` and 6 `RM` (the six build files edited after
  the move) plus 4 `M`, so `git mv` kept rename detection.
- The four remaining "modules" hits in `*.kts` are prose in
  `build-logic/src/main/kotlin/layered-module-conventions.gradle.kts` ("the contract
  other modules may use"), which is the Spring Modulith sense and stays correct.

## Notes

- `billing-client` was not renamed to `billing`: decision D2 in
  `docs/tenant-system.md` records that `checkNoBillingDependency` would flag the
  sample's own project path.
- `plans/**` was left alone — those are historical records whose paths were right
  when written.
- `docs/tenant-system.md` still says the settings file "lists the modules"; that
  sentence describes Gradle modules generally and does not name the group.
- `.idea/gradle.xml` (untracked) still mentions `tenant/modules`; a Gradle sync
  regenerates it.
- Nothing was committed and nothing was pushed.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
