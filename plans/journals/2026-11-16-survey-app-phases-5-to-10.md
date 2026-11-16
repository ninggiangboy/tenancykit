---
title: survey-app-phases-5-to-10
date: 2026-11-16
summary: "Finished the survey app: expression engine, links and visits, responses and uploads, insight and exports, events and webhooks, ops"
---

# survey-app-phases-5-to-10

## What happened

Completed phases 5-10 of `plans/261116-0145-survey-app/plan.md`, the survey app's build-out, on top of the skeleton,
core-webhook extraction, scope/docs and authoring phases already committed.

- Phase 5: one expression engine (JSON AST, closed operator set) proven by the contract's own vectors, exposed as
  preview and simulate. `ExpressionVectorTest` runs all 174+ cases; a second factory fails when an operator has no case.
- Phase 6: links (public, one per source; personal, bulk with single use) and visits that keep only bounded facts and
  pin the version they opened. Public guards: HMAC link tokens, hashed visit tokens, per-caller rate limit, body limit.
- Phase 7: response collection with typed `answers` rows, the cap as a conditional update, single use as a partial
  unique index, idempotency as a unique key, presigned uploads with a magic-byte check at submit, and a purge sweep.
- Phase 8: insight reads collection through one api port; aggregates, cross-tab, trend, funnel, respondents, CSV
  export with formula-injection escaping; insight writes only `exports`.
- Phase 9: three outbox events, including `survey-response.submitted` with no answers; signed webhooks built on
  `core-webhook`, the endpoint secret stored as AES-GCM ciphertext bound to the endpoint id.
- Phase 10: compose services for survey plus MinIO, postgres roles, broker user, Caddy route, Keycloak mapper,
  Platform service-client entry, CI workflow, isolation smoke, tenant settings surface, and the permission coverage
  gate (every catalog code enforced; every endpoint declares a permission or is public).

## Decisions worth keeping

- Typed answers store the option **id**, not its value: an aggregate counts by the stable id. Found and fixed while
  wiring the aggregate against two versions.
- A submission with no prior draft inserts the response row before completing it, because completion is an update
  guarded by the row's status.
- The settings read model is its own record, separate from the write model, so a read never drives a write and a write
  never reaches through a query — the two architecture rules pull in opposite directions otherwise.

## Verification

`survey`, `core` and `platform` build green. The survey suite runs 412 tests, 0 failures, including a 50-thread cap
test that completes exactly 10 against a cap of 10, a personal-link race that completes once, and a signed webhook
reaching a local receiver. `git diff` over this session touches no `platform/**/*.java`, no `mailroom/**` and not
`SuiteActorResolver.java`.

## Next steps

- An end-to-end Testcontainers scenario across the whole headline flow, if the smoke scripts are not enough.
- Terraform/Kubernetes resources for survey following mailroom's; the CI workflow and compose exist, the cluster
  manifests do not.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
