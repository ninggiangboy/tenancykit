---
phase: 15
title: "Docs sweep and final verification"
status: pending
priority: P1
effort: 1.5d
dependencies: [14]
---

# Phase 15: Docs sweep and final verification

## Goal
Docs describe the system that now exists, no old path survives anywhere, and every Done-means item is proven.

## Files to Create / Modify
- Modify: `docs/audiences-and-surfaces.md` (regenerate the matrix), `docs/README.md`, `docs/overview-and-scope.md`
  (the "Reached at" column and the deployables table), `docs/architecture.md` (a dated decision-ledger entry,
  2026-11-22: the seven callers, the prefixes, the `/public` namespace and the public profile),
  `docs/runtime-profiles.md`, `docs/security-and-reliability.md` (the unauthenticated endpoints are now listed by the
  generated matrix), `docs/authentication-and-authorization.md`, `docs/platform-service.md`,
  `docs/console.md` (the `audiences` of ui remotes stay; add a sentence separating it from callers)
- Modify: every app doc (`docs/billing-session-and-portal.md`, `docs/survey.md`, `docs/booking.md`, `docs/mailroom.md`,
  `docs/attribution.md`, `docs/workflow.md`, `docs/customer.md`, `docs/loyalty.md`, `docs/notification.md`): a short
  Surfaces section linking to `audiences-and-surfaces.md`
- Modify: `contracts/README.md`, `contracts/tokens/README.md`, `ops/terraform/README.md`, the root `README.md`,
  `frontend/README.md` if it names routes
- Do not edit: `docs/system-specification.md` (historical baseline)

## Tasks & Steps
1. Update each document above; read it before editing and verify links and claims against source afterwards.
2. Run the old-path search across code, docs, contracts, ops, seeds, smoke, e2e and the frontend client (excluding
   `docs/system-specification.md`, `node_modules`, compiled output); it must be empty.
3. Walk the Done-means list below and record the command and result for each in `reports/final-verification.md`.

## Final verification (Done means)
- [ ] The label test walks all 10 OpenAPI documents; a count of unlabeled operations is 0; `x-public` appears nowhere.
- [ ] All 10 documents are generated from code (phase 14 done); none is hand-written. If phase 14 was dropped, the three
      hand-written files are listed as such and their comparison tests pass.
- [ ] Every path in all 10 documents has exactly one caller, except `/api/v1/me/**` and `/oauth/token`; the
      `singleCallerPerPath` switch no longer exists.
- [ ] `/system/v1` accepts only suite tokens; `/api/v1` and `/admin/v1` only Keycloak JWTs; tests cover both directions.
- [ ] Per-caller OpenAPI files exist; the Billing special case is gone; `make docs` serves seven caller pages.
- [ ] `make docs-check` passes (matrix equals the contracts).
- [ ] Caddy and the ALB answer 404 for `*/internal/*`; replica rebuild works in compose.
- [ ] Old public paths and suite-token use of `/api/v1` return 404 and appear nowhere.
- [ ] Six `*-public` containers are healthy in compose; Terraform has their services, task definitions and ALB rules.
- [ ] Architecture tests fail when a public controller is in `api` or an authenticated one in `public`.
- [ ] Every `./gradlew test` in the 10 services, `make smoke`, `pnpm -C frontend e2e` and `terraform validate` pass.

## Verification
- The commands recorded above, run on a clean checkout of the branch.

## Risks
- A doc quotes a count or path from memory; check each against the generated matrix rather than copying it.
