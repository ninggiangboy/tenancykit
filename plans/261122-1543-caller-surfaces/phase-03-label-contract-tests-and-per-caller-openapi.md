---
phase: 3
title: "Label contract tests and per-caller OpenAPI"
status: done
priority: P1
effort: 2d
dependencies: [2]
---

# Phase 3: Label contract tests and per-caller OpenAPI

## Goal
A test fails when any operation of any of the 10 OpenAPI documents lacks a valid label, and a filtered OpenAPI file
exists per caller and app.

## Files to Create / Modify
- Create: shared test rules in `core-openapi` test fixtures: `CallerLabelRules` (every operation has a non-empty
  `x-allowed-callers`; every value is one of the seven; `x-public` appears nowhere). It also implements the rule
  **every path has exactly one value**, except the allow-list `/api/v1/me/**` and `/oauth/token`, but that rule is
  **off by default in this phase** (a per-app switch, `singleCallerPerPath`): today one path serves two callers, so
  the rule would fail on most operations. Phase 6 turns it on for the eight apps and phase 7 for Platform and
  Notification, after which the switch is removed and the rule is always on.
- Create: `CallerOpenApiSplitter` in `core-openapi` writing `contracts/openapi/<caller>/<app>.yaml` (a path with two
  values appears in both files; until phase 6 that means the tenant-system files list the same `/api/v1/...` paths as
  the tenant-user files, which is expected; empty files are not written)
- Modify: the contract tests of the 7 generated services (Platform, Billing, Notification, Mailroom, Survey,
  Attribution, Workflow) to call `CallerLabelRules` and the splitter under `-Dopenapi.update=true`
- Modify (hand-written YAML, an interim state that phase 14 removes): `contracts/openapi/booking.yaml`, `customer.yaml`, `loyalty.yaml` get labels by hand;
  their contract tests compare each label, and the "Callers: ... Requires ..." description line, with the controller
  annotation and permission catalog using the phase 2 derivation
- Delete: the `TENANT_CONTRACT` constant and its filter in
  `backend/services/apps/billing/apps/app-boot/src/test/java/dev/ngb/billing/contract/OpenApiContractTest.java`
- Move: `contracts/openapi/billing-tenant-system.yaml` to `contracts/openapi/tenant-system/billing.yaml` (generated)
- Modify: `contracts/README.md` (generated files, how to regenerate, the caller directories)

## Tasks & Steps
1. Implement `CallerLabelRules` and the splitter; unit-test the splitter on a small synthetic document.
2. Wire the 7 generated contract tests; regenerate all documents.
3. Label the three hand-written documents and add their comparison tests.
4. Remove the Billing special case and move its output.
5. Add negative cases proving that a deleted label, an unknown value and a leftover `x-public` each fail.

## Verification
- `./gradlew test` in each of the 10 services (contract tests included).
- `git diff --stat contracts/openapi` lists only the new per-caller files, label lines and the moved Billing file.
- A count of operations without a label across `contracts/openapi/*.yaml` prints 0.

## Risks
- Hand-written YAML drifting from the controllers: the comparison test is the guard; do not weaken it to pass.
