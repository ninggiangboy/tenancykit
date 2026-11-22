---
title: "Caller surfaces: baseline and label derivation"
date: 2026-11-22
summary: "Phases 1-2: baseline report, Caller model, catalog-backed labels, every endpoint annotated, contracts regenerated, architecture rule"
---

# Caller surfaces: baseline and label derivation

## What shipped

- `reports/baseline.md` answers every phase 1 question with file evidence. No stop condition was hit. The second-prefix spike proved a second `RequestMappingInfo` routes to the same method and that springdoc lists both; springdoc de-dupes operation ids but renumbers the canonical `/api/v1` path, so phase 5 must pin ids deterministically.
- `Caller` (seven wire values) and `@Callers` (class default, method exception, `prefix = NONE`) in `core-kernel`; `@PublicEndpoint` now carries the caller.
- `PermissionCatalogs` (one classpath reader), `CallerCatalog` and `CallerLabelCustomizer` in `core-openapi`. The customizer derives `x-allowed-callers`, checks permission-coded routes against the catalog, and appends the generated `Callers: ... Requires ...` line.
- All 128 controllers / 474 endpoints carry a caller. 13 method-level exceptions, provider webhooks use `PROVIDER`, Billing's portal permission maps to `customer-session`.
- Billing's private catalog and Notification's hand-written switch are gone. `x-public` is retired; the shared error customizer reads the caller label.
- 7 generated contracts regenerated (operation counts unchanged, 0 unlabeled, 0 `x-public`). Booking, Customer and Loyalty are annotated; their committed YAML still waits for phase 3.
- Shared ArchUnit rule `every-endpoint-declares-a-caller`, with a fixture in every arch-fixtures module and a meta-test entry; all 11 services pass it on production code and fail it on the fixture.

## Evidence

- `backend/libs/core && ./gradlew test` green (core-openapi and core-arch included).
- Contract tests green in all 10 services; 7 regenerated with `-Dopenapi.update=true`.
- Architecture tests green in all 11 services.
- `git diff --check` clean; commit `3e5c5ae1`.

## Deviation to resolve before phase 3

The plan's phase 3 says every path has exactly one caller, but phases 5 and 7 create that state later. Today every tenant+system route is one path with two labels, and Platform users/roles and Notification's inbox are one path with two person callers. Phase 3's rule needs a documented transitional allowance (tenant+system mirrored paths, plus the current shared person paths) that shrinks in phase 5 and disappears in phase 7. The catalog check also allows a code valid in both scopes to be narrowed per route, which is what the phase 7 split needs.

## Next

Phase 3: `CallerLabelRules` + `CallerOpenApiSplitter`, per-caller files, labels for the three hand-written YAMLs, delete Billing's tenant-system filter, `contracts/README.md`, negative tests.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
