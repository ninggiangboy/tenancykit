---
title: "Survey app — versioned dynamic surveys a Tenant runs for its own customers"
description: "A sixth deployable, survey/, that issues survey links, collects responses against immutable versions with a shared JSON-AST expression language, and measures what happens after a link opens; Billing's webhook mechanics move into core/ so both apps share them."
status: pending
priority: P1
effort: "10 phases, 6 × M, 4 × L"
branch: dev
tags: [feature, backend, api, database, refactor, infra]
blockedBy: []
blocks: []
created: 2026-11-16
---

# Survey App

## Overview

A Tenant can email its customers (mailroom) and bill them (Billing), but cannot ask them anything. This plan adds
`survey/`, an API-only deployable that owns a Tenant's surveys, their immutable versions, the links that reach a
respondent, the visits those links produce, the responses, and the analysis of all of it. The survey app never sends a
message and knows no channel: it issues links, and the Tenant puts a link wherever it likes (a mailroom campaign, a QR
code, a web page).

Scope was fixed in the `/ak-advise` interview of 2026-11-16 (memory: `survey-app-decisions`). Preview:
`plans/reports/advise-261116-0010-survey-app-preview.html`.

**Precondition.** The `email/` → `mailroom` rename (decided 2026-11-16, memory `email-app-decisions`) is done, so
survey copies the final skeleton, names and docs.

## Outcome contract

**Goals**

1. A Tenant builds a survey with branching, computed variables, quiz scoring, piping and translations, publishes it as
   an immutable version, and hands out public or personal links.
2. A respondent's answers are validated by the server exactly as the future UI will evaluate them, because both run
   one expression language against one set of test vectors.
3. A Tenant sees who responded, from which source, where people abandoned, and the aggregated results, merged across
   versions.
4. A second tenant-facing business app joins the suite with no Java change in Platform, mailroom or the shared resolver.

**Done means**

- `survey/` builds, migrates and tests green; ArchUnit and Modulith gates pass; `ops/smoke/isolation.sh` proves the
  `survey` broker user can publish only to `survey.events`.
- `git diff` of the delivery shows no change under `platform/**/*.java`, `email/` (mailroom) or
  `core/core-suite/**/SuiteActorResolver.java`. Platform changes only `application.yml` (one service-client list entry)
  and picks up `contracts/permissions/survey.yaml` at build time.
- `contracts/survey/expression.schema.json` and `contracts/survey/expression-vectors/*.json` exist; the server's
  vector runner passes 100% of them.
- Tests prove: responses of version 1 still aggregate after version 2 is published; a hidden question changes the
  validation result; 50 concurrent submits against a cap of 10 produce exactly 10 completed responses; a personal link
  cannot complete twice; computed variables and scores match the vectors; the funnel splits by source; an orphaned
  upload is deleted from storage; tenant A cannot read or write tenant B's data on any table or endpoint.
- Billing delivers webhooks through `core/core-webhook`, its existing tests are green, and the sample tenant still
  verifies Billing's signatures unchanged.
- Compose runs MinIO and mounts the offline GeoIP file; `contracts/openapi/survey.yaml` and
  `contracts/events/survey/*.schema.json` exist; `docs/survey.md` describes the app, including the manual resend
  recipe through mailroom groups and segments; smoke tests cover the webhook and the event.

**Non-goals**

Builder UI and fill UI; sending invitations or reminders; automatic reconciliation of respondents with recipients;
mailroom consuming `survey.events`; CAPTCHA; virus scanning; realtime co-editing; a retention schedule for responses
(only explicit deletion).

**Constraints**

Tenant always from the host, never from client input; the server recomputes logic, variables and scores and ignores
client values for them; no raw IP stored; closed expression operator set with depth and size caps; translations belong
to the version; webhook tables stay per app and only the mechanics live in `core/`; existing suite patterns (jOOQ with a
committed generated schema, RLS, outbox, suite tokens, `core/` shared modules); conventional commits without AI
references.

## Evidence from the scout (verified in source on 2026-11-16)

| Finding | Source | Consequence |
|---|---|---|
| A service-client token resolves to no actor | `core/core-suite/.../security/SuiteActorResolver.java:59` | Survey calls no other app's API; the REST-to-mailroom design was dropped |
| Platform builds the permission catalog from `contracts/permissions/*.yaml` | `platform/apps/app-boot/build.gradle.kts:50-52` | `survey.yaml` needs no Platform code change |
| Service clients are a list in Platform's `application.yml` | `platform/apps/app-boot/src/main/resources/application.yml:66-75` | Survey's replica rebuild needs one list entry and a secret hash, no Java |
| Billing's webhook mechanics are small, pure and depend only on `core-kernel` | `HmacSha256Signer` (28 lines), `AddressPolicy` (81), `GuardedDnsResolver` (39), `HttpComponentsWebhookSender` (130), `DeliveryPolicy` (29, uses `dev.ngb.core.util.Backoff`), `WebhookHeaders` (23, uses `dev.ngb.core.http.HttpHeaders`) | They move to a new `core/core-webhook`; endpoint and delivery tables, repositories and use cases stay in Billing |
| Billing's header names are constants in `core-kernel` | `WebhookHeaders` → `HttpHeaders.BILLING_*` | `core-webhook` takes a header set; `HttpHeaders` gains `SURVEY_*` additively |
| The stock Caddy image has no rate-limit directive | `ops/caddy/Caddyfile:49-51` | Survey enforces its public rate limits in the application |
| Mailroom segments support `in_group` and `not`; groups have a members API | `plans/261115-1849-email-app/phase-09-segments-and-housekeeping.md:30`, `docs/mailroom.md:144` | The manual resend recipe needs no mailroom change |
| A new service's ops touchpoints are known | `plans/261115-1849-email-app/phase-10-ops-e2e-and-docs.md` | Phase 10 follows the same list |

## Key decisions

- **Contexts.** `platform` (replica), `authoring` (surveys, drafts, versions, translations, the expression engine),
  `collection` (links, visits, page events, responses, answers, uploads), `insight` (aggregates, filters, cross-tabs,
  trends, funnel, respondents, exports), `webhook` (endpoints and deliveries). `collection` and `insight` reach
  `authoring` only through its `api` package.
- **Version document.** A published version is one JSONB document (pages, questions, options, logic, computed
  variables, scoring, translations) plus a `version_no`. Question and option ids are server-assigned UUIDs that persist
  across versions; a question whose `type` changes is given a new id at publish and records `replaces`.
- **Expression language.** A JSON AST with a closed operator set; the schema and the vectors live in `contracts/survey/`
  so a future JS evaluator runs the same vectors. Numbers are decimals rounded to 6 fraction digits, `HALF_EVEN`.
- **Answers stored twice.** The response keeps the submitted JSONB for display and export; a typed `answers` table
  (one row per question, option or matrix cell) backs every aggregate, filter, cross-tab and trend.
- **Concurrency in the database.** The response cap is a conditional `update survey_counters set completed = completed
  + 1 where completed < cap`; single use is a partial unique index on `responses(link_id) where status = 'COMPLETED'`;
  idempotency is unique `(tenant_id, idempotency_key)`.
- **Links.** Public links (one per Tenant-named source) and personal links (bulk, `external_ref`, expiry, single use).
  Tokens are HMAC-signed link ids (mailroom's tracking-token pattern), so a link's URL can be read back at any time and
  revoked by status.
- **Visits.** Opening a link creates a visit and returns an opaque visit token (stored as SHA-256). The visit records
  the link's source, UTM parameters (bounded, informational), the referrer's domain, a device class and browser family,
  and the GeoIP country. The IP is used in memory only.
- **Files.** MinIO/S3 presigned PUT; the server checks size and magic bytes at submit; pending uploads are deleted
  after 24 hours.
- **Events and webhooks.** `survey.events` carries `survey-response.submitted`, `survey-version.published` and
  `survey.closed` through the outbox; the same outbox rows feed signed `Survey-*` webhooks built on `core-webhook`.

## Extension trace

The credible next variant is the future fill UI evaluating logic in the browser. With this plan it adds a JS evaluator
that passes `contracts/survey/expression-vectors/*.json`; the version document, the public API and the server do not
change. Phase 5 proves the claim by making the vector files the only source of the server's expression tests. The
second credible variant is a third app that sends webhooks; it depends on `core-webhook` and copies the two tables,
which phase 2 proves with Billing. No registry, plugin system or provider abstraction is built.

## Phases

| # | Phase | Size | Depends on |
|---|---|---|---|
| 1 | [Scope, docs and contracts](phase-01-scope-docs-and-contracts.md) | M | — |
| 2 | [Shared webhook library and Billing migration](phase-02-core-webhook-library.md) | M | 1 |
| 3 | [Build skeleton, replica and gates](phase-03-build-skeleton-and-gates.md) | L | 1, rename committed |
| 4 | [Authoring: drafts, versions, translations](phase-04-authoring-drafts-and-versions.md) | M | 3 |
| 5 | [Expression engine, preview and simulate](phase-05-expression-engine.md) | L | 4 |
| 6 | [Links, visits and public guards](phase-06-links-visits-and-guards.md) | M | 4 |
| 7 | [Response collection and uploads](phase-07-response-collection-and-uploads.md) | L | 5, 6 |
| 8 | [Insight: analytics, funnel, respondents, exports](phase-08-insight-and-exports.md) | L | 7 |
| 9 | [Events and webhooks](phase-09-events-and-webhooks.md) | M | 2, 7 |
| 10 | [Ops seams, end-to-end and docs](phase-10-ops-e2e-and-docs.md) | M | 8, 9 |

Phase 2 runs in parallel with phases 3-8 (disjoint trees: `core/` + `billing/` versus `survey/`). Phases 5 and 6 can run
in parallel after phase 4 (`authoring` versus `collection`), and phases 8 and 9 after phase 7.

## Risk register

| Risk | Likelihood | Impact | Mitigation | Phase |
|---|---|---|---|---|
| Server and future client disagree on a branch or a score | High | A respondent sees a question the server rejects | One AST schema and one vector set in `contracts/`; the server's tests are generated from the vectors | 1, 5 |
| An expression is used as a DoS (deep nesting, huge arrays) | Medium | A public submit stalls a worker | Depth ≤ 16, nodes ≤ 500 per expression, 2,000 per version, array literal ≤ 100, validated at draft and enforced at evaluation | 5 |
| Cap or single use broken under concurrent submits | Medium | More responses than allowed, duplicates | Conditional counter update and partial unique index; a 50-thread test | 7 |
| Aggregates wrong across versions | Medium | Misleading results | Stable ids, `replaces` lineage, typed `answers` table; a test publishing v2 with a removed, an added and a retyped question | 4, 8 |
| Webhook extraction changes Billing's wire format | Low | Tenant Systems reject Billing deliveries | Byte-for-byte signature test with a fixed key, body and timestamp before and after; the sample tenant's verification tests stay green | 2 |
| SSRF through webhook URLs or upload callbacks | Medium | Internal network reached | `core-webhook` keeps Billing's address policy and guarded resolver; uploads never fetch a client-supplied URL | 2, 7 |
| Public endpoints used for enumeration or write amplification | Medium | Data leak, load | Signed link tokens, constant 404 for any invalid token, per-IP and per-survey token buckets, payload ≤ 256 KiB, page events ≤ 200 per visit, honeypot | 6, 7 |
| Personal data in answers and files | Certain | Compliance | Explicit delete removes answers and objects; no answer, email, token or IP in logs; RLS on every table | 7, 8 |
| A malicious upload is served to the Tenant | Medium | Malware delivered under the suite's domain | Magic-byte check against the allowed MIME list; download only through a presigned GET with `Content-Disposition: attachment`; no virus scan (non-goal) | 7 |
| GeoIP file missing or stale | Medium | Country is `null` | Startup reads it if present; health shows its build date; a missing file is not an error | 6, 10 |
| Funnel counts depend on clients sending page events | Certain in v1 | Abandonment page is approximate | Documented; `STARTED` and `COMPLETED` come from server-side facts, only page views depend on the client | 6, 8 |

## Open questions

None blocking.

## Validation Log

### Session 1 (2026-11-16)

| Question | Decision | Propagated to |
|---|---|---|
| GeoIP data source | DB-IP Lite country database (`.mmdb`, attribution required), fetched by `ops/geoip/fetch.sh`, never committed | phases 6, 10 |
| Expression number semantics | Decimal: every arithmetic result is rounded to 6 fraction digits, `HALF_EVEN`; Java uses `BigDecimal`, a future JS client uses a decimal library; comparisons are exact on the rounded values | phases 1, 5 |
| Public rate-limit store | In memory per instance (bounded map of token buckets); with N instances the effective limit is N times the setting, documented | phases 6, 10 |
| Rename precondition | Phase 3 onwards waits for the mailroom rename to be committed; the rename is not part of this plan | plan, phase 3 |

### Verification Results

- Claims checked: 18 (Full tier). Verified: 16. Failed: 2, both corrected in the plan.
- Failed and fixed: Billing's `rotate-secret` has no overlap window (`EndpointController.java:113-117`), so phase 9 no
  longer claims one; `docs/overview-and-scope.md:13` reads "Five deployables" with a capital F, so phase 1's check
  uses `grep -i`.
- Verified examples: mailer uses AWS SDK v2 (`SesV2EmailSender.java`); `tenant/` verifies Billing signatures
  (`tenant/.../WebhookController.java`, `WebhookHttpIntegrationTest.java`); per-app Keycloak audience mappers exist
  (`ops/keycloak/realm-export.json`); `core-arch` has one self-containment rule per core module
  (`ArchitectureRules.java:150`), so phase 2 adds one for `core-webhook`.

### Red-team corrections

- `insight` was both "never writes" and the owner of deletes and exports; deletion moved to `collection`
  (phase 7, task 7) and the architecture rule now allows `insight` to write only `exports`.
- The single-use index needed a column the plan never defined; `responses.personal_link_id` now carries it.
- `visits.response_id` was used in phase 7 but missing from phase 6's table; added.
- Presigned upload URLs must name a host the browser reaches; `SURVEY_S3_PUBLIC_ENDPOINT` added (phases 7, 10).
- Phase 10 resized from S to M (CI, Terraform, Kubernetes and the end-to-end suite).

### Whole-Plan Consistency Sweep

All phase files re-read after the edits: dependency table, sizes, the deletion owner, the rate-limit store and the
number semantics agree across `plan.md` and phases 1-10.
