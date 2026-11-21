---
phase: 3
title: "Backend: a precondition of 0 creates an absent row"
status: completed
priority: P1
effort: "0.5d"
dependencies: []
---

# Phase 3: Backend: a precondition of 0 creates an absent row

## Goal

Enabling an app for a tenant that never changed it succeeds from the console, because the API accepts the precondition
its own list endpoint tells the client to send. Fixes E12. This phase shares no file with phases 1, 2, 4, 5 or 6 and can
run first or last.

## What happens today (verified)

- `GET /api/v1/tenants/{tenantId}/apps` returns every app of the suite; for an app the tenant never changed it returns
  `enabled: false, version: 0` (`EntitlementController.apps`, and its javadoc: "disabled at 0").
- The console echoes that version as `If-Match: "0"` (`remotes/platform/src/features/tenants/components/tenant-apps.tsx`
  sends `version: app.version ?? 0`; `use-tenants.ts` turns it into `etag(version)`).
- `EntitlementRows.lockOrInsert` finds no row and calls `expected.checkAbsent()`. `ExpectedVersion.checkAbsent()` throws
  `PreconditionFailedException(null)` for every explicit tag, so the answer is `412 The resource changed since it was
  read`. The toggle snaps back and the person sees a message about a change that never happened.
- Observed on a fresh tenant: `PUT .../apps/attribution`, `If-Match: "0"`, response 412.
- A test pins the current rule for any tag: `ConditionalUpdateHttpIntegrationTest.entitlementSetFollowsTheConditionalMatrixAndAnAbsentRowRejectsATag`
  sends `"3"` to an absent entitlement and expects 412.

## The rule after this phase

Version 0 names a row that does not exist. For a missing row, `If-Match` may be absent, `*`, or a list that contains
`"0"`; any other tag is still `412` with no current ETag. For an existing row nothing changes: its version is 1 or more,
so `"0"` still fails there, and a spurious `"0"` cannot overwrite anything. Two concurrent first writes still serialise
through `insertIfAbsent`, and the loser still applies its change on top of the winner's row (version 2).

This loosens one shared helper. The other caller of `checkAbsent()` is Loyalty's `SaveProgramUseCase`; a missing program
there now also accepts `"0"`, which no client sends today, and its existing tests must keep passing.

## Files to Create / Modify

- Modify: `backend/libs/core/core-kernel/src/main/java/dev/ngb/core/version/ExpectedVersion.java`
- Modify: `backend/libs/core/core-kernel/src/test/java/dev/ngb/core/version/ExpectedVersionTest.java`
- Modify: `backend/services/platform/apps/app-boot/src/test/java/dev/ngb/platform/web/ConditionalUpdateHttpIntegrationTest.java`
- Modify: `docs/concurrency-and-locking.md` (the "Conditional updates" list)
- Modify: `frontend/e2e/tests/13-interaction-audit.spec.ts` (one check; the file is created in phase 1, so if this phase runs
  first, create the file with only this check and let phase 1 add to it)

## Tasks & Steps

1. `ExpectedVersion.checkAbsent()`: pass when `any` or when `accepted` contains `0L`; otherwise throw
   `PreconditionFailedException(null)` as now. Update the method's javadoc (it currently says "only a missing header or
   `*` may create it") and the class javadoc to say that version 0 stands for a row that does not exist yet.
2. `ExpectedVersionTest`: rename `anAbsentRowMayOnlyBeCreatedWithoutAPrecondition` to describe the new rule and cover:
   `any()` passes; `"0"` passes; the list `"0", "5"` passes; `"1"` throws with a null current version; the weak tag
   `W/"0"` throws (a weak tag never matches); `check(0)` against an existing row of version 0 is not a case (rows start
   at 1) and needs no test.
3. `ConditionalUpdateHttpIntegrationTest`: change the absent-row part of the entitlement test, and its name and comment,
   to: a `PUT` with `"0"` on a never-set app answers 200 with `ETag: "1"` and `enabled: true`; a second `PUT` with `"0"`
   on the same app answers 412 whose `ETag` header is `"1"`; a `PUT` with `"3"` on another never-set app still answers
   412 with no `ETag`. Keep the matrix assertions above it unchanged.
4. Docs: in `docs/concurrency-and-locking.md`, "Conditional updates", add one bullet after the comparison rule: version
   0 is what a list reports for a row that does not exist, and a precondition of `"0"` creates it; any other tag on a
   missing row is 412 without a current ETag. Then `grep -rn "checkAbsent\|absent" docs/*.md` and fix any other
   sentence that says a tag can never create a row.
5. Console check in `13-interaction-audit.spec.ts`: as the platform operator open a fresh tenant's sheet, switch on an
   app the tenant never had (the first switch of the list), and assert: the switch ends checked, no `role="status"`
   toast mentions "changed since", and a reload shows it still checked. Run it once before the backend change (it must
   fail) and once after.

## Verification

- `cd backend/libs/core && ./gradlew :core-kernel:test` passes.
- `cd backend/services/platform && ./gradlew test --tests '*ConditionalUpdateHttpIntegrationTest' --tests '*Entitlement*'`
  passes (these start the database container; use the module path that `settings.gradle.kts` defines for `app-boot`).
- `cd backend/services/loyalty && ./gradlew test --tests '*ProgramApiIntegrationTest'` passes unchanged.
- Rebuild what the e2e needs: `docker compose up -d --build platform`, then the console check from step 5 passes.
- `grep -rn "If-Match" contracts/openapi/platform.yaml` is unchanged: the contract is generated from the controller and
  this change adds no field.

## Risks / rollback

- Looser precondition on one helper: the whole change is one condition and its tests; reverting it restores the 412.
- If a reviewer prefers not to touch the shared helper, the fallback is a frontend-only change (send no `If-Match` while
  `version` is 0). It works but leaves the list endpoint advertising a version that its own write endpoint refuses, so
  this plan does not take it.
