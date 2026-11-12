---
phase: 2
title: "Shared ETag and precondition primitives"
status: done
priority: P1
effort: "0.5d"
dependencies: []
---

# Phase 2: Shared ETag and precondition primitives

## Goal

Add one plain-Java type that parses `If-Match` and checks a version, one exception for a failed precondition, and a 412
mapping in both apps. Phases 3 and 4 then only wire them in.

## Files to create / modify

- Create `core/core-kernel/src/main/java/dev/ngb/core/version/ExpectedVersion.java`:
  - `static ExpectedVersion fromIfMatch(String header)`. A null or blank header and `*` give `any()`. A
    comma-separated list of strong tags `"<digits>"` gives the set of accepted versions. A weak tag is kept as a tag
    that never matches. Anything else throws `IllegalArgumentException`, which both handlers already map to 400.
  - `static ExpectedVersion any()`.
  - `void check(long current)` throws `PreconditionFailedException(current)` unless the version is any or contained.
  - `void checkAbsent()`, for a row that does not exist yet: throws unless `any()`.
  - `static String etag(long version)` returns `"\"" + version + "\""`.
- Create `core/core-kernel/src/main/java/dev/ngb/core/error/PreconditionFailedException.java`. It extends
  `RuntimeException` (not `ConflictException`, so the 409 handlers cannot catch it) and carries `Long currentVersion`,
  which is null when the row is absent.
- Create `core/core-kernel/src/test/java/dev/ngb/core/version/ExpectedVersionTest.java` covering:
  - null, blank and `*`;
  - a single tag and a list;
  - a weak tag;
  - whitespace around list entries;
  - malformed inputs: an unquoted `3`, `"abc"`, and a quoted negative number;
  - a version larger than `Integer.MAX_VALUE`;
  - `checkAbsent`.
- Modify `billing/apps/app-boot/src/main/java/dev/ngb/billing/boot/web/ApiExceptionHandler.java` and
  `platform/apps/app-boot/src/main/java/dev/ngb/platform/boot/web/ApiExceptionHandler.java`: handle
  `PreconditionFailedException` with 412, a ProblemDetail reading "The resource changed since it was read. Fetch it
  again and retry with the new ETag.", and an `ETag` header when `currentVersion` is non-null.
- Modify `billing/apps/app-boot/src/test/java/dev/ngb/billing/boot/web/ApiExceptionHandlerTest.java`: add the 412
  case.

## Verification

- `cd core && ./gradlew build`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*ApiExceptionHandlerTest'`
- `cd platform && ./gradlew :apps:app-boot:compileJava`

## Risks

Low. The package `dev.ngb.core.version` is new, so the core ArchUnit rule (`core` imports nothing from the apps) still
holds.
