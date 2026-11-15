---
title: "Attribution app — marketing links, measurement, conversions and ad cost for a Tenant"
description: "A seventh deployable, attribution/, that issues tracked marketing links, measures clicks, page views, sessions and conversions, attributes conversions to clicks by first, last or linear model, and joins them with ad cost entered by hand or imported from Google Ads and Meta Ads."
status: pending
priority: P1
effort: "12 phases, 3 × M, 9 × L"
branch: dev
tags: [feature, backend, api, database, infra]
blockedBy: []
blocks: []
created: 2026-11-16
---

# Attribution App

## Overview

A Tenant can email its customers (mailroom), survey them (survey, planned) and bill them (Billing), but cannot tell which
marketing link, channel or ad brought a customer or what that cost. This plan adds `attribution/`, an API-only deployable
that owns a Tenant's campaigns, tracked links, the clicks, page views, sessions and conversions they produce, the
attribution of each conversion to the clicks that led to it, and the cost of the advertising behind them.

Scope was fixed in the `/ak-advise` interview of 2026-11-16 (memory: `attribution-app-decisions`). Preview:
`plans/reports/advise-261116-0200-attribution-app-preview.html`. Several choices were the heavier option against the
advisor's recommendation (page views through the snippet, the linear model, Google and Meta import in v1); they are
kept, and their cost is in the risk register.

**Precondition (met).** The `email/` → `mailroom` rename is committed (`ea7923c3`), so the skeleton to copy is final.

## Outcome contract

**Goals**

1. A Tenant creates campaigns and tracked links (UTM, custom slug, QR, expiry, click cap, password, several
   destinations, bulk personal links) and shares them anywhere.
2. The Tenant sees clicks, page views, sessions and conversions per link, campaign, source, device and country, with
   funnel and cohort views, without any raw IP or pre-consent cookie being stored.
3. A conversion reported by the Tenant's backend (postback) or the site snippet is credited to the click or clicks that
   led to it under the model the campaign selected, and the credit is reproducible from stored touchpoints.
4. The Tenant sees cost next to results: cost entered by hand or pulled daily from Google Ads and Meta Ads, with CPC,
   CPA and ROAS per campaign.
5. A third tenant-facing app joins the suite with no Java change in Platform, Billing, mailroom or the shared resolver.

**Done means**

- `attribution/` builds, migrates and tests green; ArchUnit and Modulith gates pass; `ops/smoke/isolation.sh` proves the
  `attribution` broker user writes only the `attribution.ingest` exchange.
- `git diff` of the delivery shows no change under `platform/**/*.java`, `billing/`, `mailroom/`, `survey/` or
  `core/core-suite/**/SuiteActorResolver.java`. Platform changes only `application.yml` (one service-client entry) and
  picks up `contracts/permissions/attribution.yaml` at build time.
- `contracts/attribution/attribution-vectors/*.json` exist and the server's vector runner passes 100% of them for all
  three models, including a recompute after a model change.
- Tests prove: a link with a cap of N yields exactly N counted clicks under 100 parallel requests; the same postback
  key sent twice yields one conversion; a bot user agent is stored as `BOT` and absent from default reports; a click id
  with a bad signature is ignored; no table contains a raw IP address (schema test); tenant A cannot read or write
  tenant B's data on any table or endpoint; an erasure request removes the visitor's page events and bindings.
- The redirect load test on the compose stack meets the target fixed in the decisions (p95 ≤ 20 ms at 500 requests per
  second for 60 s, zero 5xx, every published event stored exactly once).
- Cost import works against a Google Ads test account and a Meta test ad account, and never overwrites a manual entry.
- `contracts/openapi/attribution.yaml` and `contracts/permissions/attribution.yaml` exist; `docs/attribution.md` describes
  the app; smoke and end-to-end tests pass.

**Non-goals**

Creating or editing ads on any ad platform; custom domains; a Console or any HTML page (including a password unlock
page); SMS or push; automatic wrapping of mailroom or survey links and any call to or from those apps; outbound webhooks;
cross-device identity resolution; fingerprinting; counting traffic that did not arrive through a tracked link; real-time
streaming; currency conversion; time zones other than UTC in reports; export jobs or object storage (CSV is streamed).

**Constraints**

Tenant always from the host, never from client input (the single OAuth callback, which reads it from a server-signed
`state`, is the one stated exception); the redirect never fails or slows because analytics fails; no raw
IP stored; no cookie before consent; destination URLs are `http` or `https` only; ad-platform tokens encrypted at rest
with the existing `SecretCipher`; only read endpoints of ad platforms are called; existing suite patterns (jOOQ with a
committed generated schema, RLS, suite tokens, ETag and `If-Match`, `core/` shared modules, api/worker/migrate runtimes);
conventional commits without AI references; no plan or phase number in code, migration or test names.

## Evidence from the scout (verified in source on 2026-11-16)

| Finding | Source | Consequence |
|---|---|---|
| The mailroom rename is committed and the tree is clean | `git log` shows `ea7923c3`; `git status` empty | The skeleton in `mailroom/` is final; copy it |
| `SecretCipher` (AES-256-GCM envelope, context-bound) already exists | `core/core-kernel/.../security/SecretCipher.java`, `core/core-spring/.../AesGcmSecretCipher.java` | Ad-platform refresh tokens reuse it; no new crypto |
| No user-agent, GeoIP or bot code exists in the repo | repo-wide search; only `HttpComponentsWebhookSender` sets a header | A shared visitor library is new work; survey's plan would build the same code |
| Platform builds the permission catalog from `contracts/permissions/*.yaml` | `platform/apps/app-boot/build.gradle.kts:51-56` | `attribution.yaml` needs no Platform code |
| Service clients are a list in Platform's `application.yml` | `platform/apps/app-boot/src/main/resources/application.yml:63-72` | One list entry and a secret hash for the replica rebuild |
| Public paths are excluded from the token and entitlement filters by prefix | `mailroom/.../security/SecurityConfiguration.java:47`, `web/AppEntitlementFilter.java:24`, `web/TenantContextFilter.java:27` | `/go/**` and `/t/**` follow the same pattern; tenant comes from the host |
| The proxy routes by Host and path prefix only; Billing answers everything unmatched; the stock image has no rate-limit directive | `ops/caddy/Caddyfile` | A new `/go/*` route (no prefix strip) and `/attribution/*` route; rate limits live in the app |
| Broker users are per service with write and read regexes over exchanges and queues | `ops/rabbitmq/declare-users.sh` | `attribution` writes its own exchange `attribution.ingest`, queues and DLQs, and reads `platform.events` |
| A database owner role migrates; an app role owns nothing and cannot bypass RLS | `ops/postgres/init/00-databases.sh` | The app role cannot create partitions: partition DDL needs a `SECURITY DEFINER` function owned by the owner role |
| `core-messaging` is Spring AMQP based with per-message envelope dispatch for domain events | `core/core-messaging/build.gradle.kts`, `AmqpInboundDispatcher` | The batch ingest consumer is new code in the app, not a use of the outbox |
| Updates follow ETag and optional `If-Match` (412 on mismatch) | `docs/concurrency-and-locking.md:91-106` | Campaign and link updates follow it |
| A Postgres-backed token bucket exists in Platform only | `platform/.../PostgresRateLimiter.java` | Not usable on the redirect path (a DB hit per request); public limits are in memory per instance, as in the survey plan |
| Billing has a `loadTest` Gradle task outside the normal build | `billing/apps/app-boot/build.gradle.kts:72-80`, `ops/loadtest/run.sh` | The redirect load test follows that precedent |
| A new service's ops touchpoints are known | `plans/261115-1849-email-app/phase-10-ops-e2e-and-docs.md` | Phase 12 follows the same list |

## Key decisions

- **Contexts.** `platform` (replica), `links` (campaigns, links, destinations and rules, QR, bulk links, the redirect
  decision), `tracking` (click, page-view, session and goal events, visitor identity, bot classification, partitions,
  retention, erasure), `attribution` (goals, conversions, touchpoints, models, credits), `spend` (cost entries, ad
  connections, platform adapters, sync), `insight` (rollups, reports, funnel, cohort, exports). `tracking`, `attribution`
  and `insight` reach `links` only through its `api` package; `insight` writes only rollup and export tables.
- **Click id.** Every redirect creates a click and appends `aid=<click token>` (name configurable per Tenant) to the
  destination. The token is `base64url(click_id ‖ key_id ‖ HMAC-SHA256(secret, click_id ‖ key_id))`, so the collect
  endpoints and postback accept only click ids the app issued. Traffic without a valid click id is ignored.
- **Identity.** Before consent the snippet sends only the landing page view with the click id from the URL, no cookie.
  After consent it sets a first-party cookie holding a random visitor id (default 30 days) and a session cookie
  (30 minutes of inactivity). A conversion's touchpoint chain is every click bound to the same visitor id inside the
  window, plus the conversion's own click id; without a visitor id the chain is the single click, so all three models
  agree. Anonymous unique counts use an HMAC of IP and user agent under a key rotated daily, so uniques are per day only.
- **Models.** `FIRST`, `LAST`, `LINEAR`, chosen per campaign with a window of 1-90 days (default 30). Credits are stored
  per conversion and click, summing exactly to the conversion value (the rounding residual goes to the last touch).
  Changing a campaign's model or window starts a recompute run; reports always read credits of the current model.
- **Ingest.** The redirect writes nothing to the database. It reads link rules from an in-process cache (30 s TTL,
  single-flight loads, negative entries cached), puts the click event in a bounded in-memory buffer and answers `302`.
  A background publisher sends batches to the `attribution.ingest` exchange; the worker consumes in batches and inserts
  per tenant under RLS, idempotent on `event_id`. When the buffer overflows the oldest events are dropped and counted
  (`attribution.ingest.dropped`); analytics never blocks a redirect. Links with a click cap are the one exception: they
  do one conditional counter update per click.
- **Storage.** Raw events live in tables partitioned by day with a 90-day default retention that is a deployment setting,
  not a Tenant setting (partitions are shared across Tenants; a Tenant's earlier deletion is the erasure endpoint);
  partitions are created ahead and dropped by `SECURITY DEFINER` functions owned by the table owner. Rollups are one
  table per question (traffic by hour, conversions and credits by day, referrers by day), kept 25 months. Multi-day
  unique counts are the sum of daily uniques and are labelled so.
- **Privacy.** No raw IP, no pre-consent cookie, no fingerprinting, bounded UTM and referrer domain only, bot and
  prefetcher traffic stored as `BOT`, an erasure endpoint keyed by visitor id, and retention enforced by dropping
  partitions.
- **Spend.** Cost rows are `(campaign, day, source, amount, currency, impressions, clicks)` with source `MANUAL`,
  `GOOGLE_ADS` or `META_ADS`; an import writes only its own source. A campaign has one currency; a mapping or entry in
  another currency is refused (`409`), so no conversion is needed.
- **Redirect safety.** Destinations are checked when a link is saved (scheme, no userinfo, no suite or private host). A
  Tenant archives or pauses its own link; the platform-side abuse lever is removing the Tenant's `attribution`
  entitlement, which makes every `/go/*` of that Tenant answer `404` once the replica applies the event (within the
  cache TTL on each instance). No new cross-tenant endpoint exists.
- **Owning campaign.** A conversion's chain may span campaigns. The model and window of the campaign of the **last click in
  the chain** govern the conversion, and every click in the chain, whatever its campaign, receives credit under that model.
  This is one conversion value credited once, never once per campaign.
- **OAuth callback.** Providers need one exact redirect URI, and Tenants have different hosts, so the callback is a single
  public URL on the Platform host (`https://platform.<base-domain>/attribution/t/oauth/callback`). It is the one place the
  Tenant comes from a signed `state` rather than the host; the state secret is separate from the click-token secret.
- **No domain events in v1.** The app declares only `attribution.ingest` and its two consumer queues.

## Extension trace

The credible next variant is a third ad platform (for example TikTok). It adds one adapter implementing the
`AdSpendSource` port and one `source` value; the cost table, reports, mapping API and sync job are unchanged. Phase 11
proves the claim by making Google and Meta, two real variants with different granularity, time zones and currency
rules, pass through the same port and the same sync job, with provider differences kept inside each adapter. The second
credible variant is a fourth attribution model (position-based); the engine takes a `model` function over the touch chain,
and the vector files are the test, which phase 8 proves with three models. No registry, plugin system or runtime
configuration of adapters is built.

## Cross-plan coordination

- **Survey plan** (`261116-0145-survey-app`, pending) builds user-agent, referrer, GeoIP and hash code inside `survey/`
  in its phase 6. Phase 2 of this plan creates `core/core-visitor` with that code instead; survey's phase 6 should adopt
  it, and if survey phase 6 has already run, phase 2 extracts survey's code into `core-visitor` and moves survey onto
  it. This plan does not edit the survey plan.
- **Overview docs.** `docs/overview-and-scope.md` counts deployables in words ("Five deployables"). Phase 1 and phase 12
  count what exists at the time, using `grep -i`, not what the plans promise.

## Phases

| # | Phase | Size | Depends on |
|---|---|---|---|
| 1 | [Scope, docs, contracts and decisions](phase-01-scope-docs-contracts-and-decisions.md) | M | — |
| 2 | [Shared visitor library in core](phase-02-core-visitor-library.md) | M | 1 |
| 3 | [Build skeleton, replica and gates](phase-03-build-skeleton-and-gates.md) | L | 1 |
| 4 | [Campaigns, links, QR and bulk links](phase-04-campaigns-and-links.md) | L | 3 |
| 5 | [Redirect path, rules and click publishing](phase-05-redirect-path.md) | L | 2, 4 |
| 6 | [Ingest storage, partitions and retention](phase-06-ingest-storage.md) | L | 5 |
| 7 | [Site collection: snippet, page views, sessions, visitor binding](phase-07-site-collection.md) | L | 6 |
| 8 | [Conversions, postback and attribution engine](phase-08-conversions-and-attribution-engine.md) | L | 7 |
| 9 | [Rollups, reports, funnel, cohort and exports](phase-09-rollups-and-reports.md) | L | 8 |
| 10 | [Spend: manual cost and cost metrics](phase-10-spend-and-cost-metrics.md) | M | 9 |
| 11 | [Ad platform import: Google Ads and Meta Ads](phase-11-ad-platform-import.md) | L | 10, external approvals |
| 12 | [Ops seams, load test, end-to-end, privacy and docs](phase-12-ops-load-test-e2e-and-docs.md) | L | 11 |

Phase 2 runs in parallel with phase 3 (disjoint trees: `core/` versus `attribution/`). The cost-entry CRUD of phase 10 can
start after phase 4, and only the cost metrics wait for phase 9. Phase 11 waits for the Google and Meta approvals; if
they are not available when it starts, deliver phases 1-10 and 12 first (see Open questions).

## Risk register

| Risk | Likelihood | Impact | Mitigation | Phase |
|---|---|---|---|---|
| Google or Meta approval is late or denied | High | Import cannot be tested end to end | Apply in phase 1; build against fixtures recorded from the sandbox and test accounts; import is the last functional phase | 1, 11 |
| Google Ads has one OAuth scope that allows writes as well as reads | Certain (to verify) | A leaked token is broader than the feature | Encrypt with `SecretCipher`, bind to the connection id, call read endpoints only, assert in a test that no mutate method is referenced, support revoke | 11 |
| Redirect p95 depends on a cache miss or a database call | Medium | Latency target missed | Single-flight loading cache, negative caching, no write on the path except capped links; load test in phase 12 and a smaller one in phase 5 | 5, 12 |
| A capped link becomes a hot row | Medium | Capped links limit throughput | Documented trade-off for exactness; conditional update; the 100-way test | 5 |
| Tenant links abused for phishing on the suite's domain | Medium | Reputation and takedown | Scheme and host checks at save, entitlement removal as the platform-side lever, destination host shown in reports, abuse runbook | 4, 5, 12 |
| The one OAuth callback trusts a signed `state` instead of the host | Low | A forged or replayed callback links an account to the wrong Tenant | HMAC with a dedicated secret, 10-minute expiry, one-time nonce, tenant and actor inside the state, constant `400` on failure | 11 |
| Public collect endpoints receive forged data | High | Wrong numbers | Only signed click ids count, per-click-id and per-IP-hash limits, allowed-origin check, bounded payloads, bot class | 7 |
| Safari and ad blockers cut cookies and snippets | Certain | Page views and multi-touch chains undercount | Documented; postback is the authoritative conversion path; chain falls back to the single click | 7, 12 |
| Ingest drops events during a broker outage | Medium | Counts low | Bounded buffer with a drop counter and an alert threshold; the redirect still succeeds; load test proves zero loss when the broker is healthy | 5, 6 |
| Partition management fails or an app role cannot create one | Medium | Inserts fail at midnight | `SECURITY DEFINER` functions, partitions created seven days ahead, a default partition as a safety net, a health check on the next partition | 6 |
| RLS is not applied to a cross-tenant batch insert | Medium | Rows in the wrong tenant or insert refused | The batch is grouped by tenant and each group is inserted in its own tenant-scoped transaction; a two-tenant test | 6 |
| Linear credit does not sum to the value | Medium | Reports disagree with revenue | Largest-remainder rounding, residual to the last touch, a vector for it | 8 |
| Recompute is slow or runs twice | Medium | Stale or doubled credit | One run per campaign at a time through a row lock, credits replaced atomically per conversion, resumable by cursor | 8 |
| Cohort and funnel queries scan raw events | Medium | Slow reports | Read rollups and credits, not raw events; raw events only for a bounded date range | 9 |
| Meta and Google report cost in their own time zone and revise it for days | Certain | Day totals shift | Store the account time zone, re-pull the last 7 days every run, upsert by key | 11 |
| Click id parameter collides with the destination's own parameter | Low | Broken landing page | Name configurable per Tenant, default `aid`, checked against the destination's query at save | 4 |
| Personal data in events | Certain | Compliance | No raw IP; consent gate; erasure endpoint; retention by partition drop; no token, IP or UA in logs | 6, 7, 12 |
| Scope is larger than the email and survey apps together | Certain | Schedule | Twelve phases each ending in something runnable; import last; no unrequested feature added | all |

## Open questions

- Phase 11 needs a Google Ads developer token (test access is enough to build; basic access is needed for real
  accounts) and a Meta app with `ads_read`, both started in phase 1. If neither exists when phase 11 starts, the executor
  stops and asks whether to ship without import or wait.
- The provider facts in phase 11 (OAuth scopes, token lifetime, query shapes, rate limits) are unverified at planning time;
  task 1 of that phase verifies them and may change the rest of the phase.
- `survey/`'s plan is unchanged by this plan. Whoever runs survey phase 6 after phase 2 here should adopt `core-visitor`
  (a one-line edit to that phase); this plan does not make it.

## Validation Log

### Session 1 (2026-11-16)

| Question | Decision | Propagated to |
|---|---|---|
| Where the visitor classification code lives | `core/core-visitor`, created by phase 2; survey adopts it (or phase 2 extracts survey's code if survey ran first) | plan (Cross-plan coordination), phases 2, 5 |
| Which campaign's model governs a chain across campaigns | The campaign of the last click in the chain; every click in the chain is credited under that model; one conversion is credited once | plan (Key decisions), phases 8, 10 |
| Chains without a visitor id | Accepted: without consent the chain is one click and the models agree; reports show the share of conversions with multi-click chains | phases 8, 9, 12 |
| Performance numbers | Defaults kept: redirect p95 ≤ 20 ms and p99 ≤ 50 ms at 500 rps for 60 s, zero 5xx, stored equals published; reports p95 ≤ 500 ms on 5 million clicks; raw 90 days, rollups 25 months | phases 1, 5, 9, 12 |

### Verification Results

- Claims checked: 21 paths and line ranges cited in the evidence table and phases (Quick tier, by `test -f` and `sed -n`).
  Verified: 21. Failed: 0.
- Verified examples: `SecretCipher` and `AesGcmSecretCipher` exist; the permission catalog is copied from
  `contracts/permissions` by `platform/apps/app-boot/build.gradle.kts`; mailroom's public prefixes and the two filters that
  skip them; `PostgresRateLimiter` exists only in Platform; the `loadTest` Gradle task exists in Billing; the touchpoint
  list in phase 12 comes from `git grep -l -i mailroom` on 2026-11-16.
- Not verified at planning time (stated in the phases): Google and Meta API facts, Safari's handling of unknown query
  parameters, and the public-suffix handling of `.com.vn` (a test in phase 2 decides).

### Red-team corrections (applied during drafting)

- A `BLOCKED` link status set by a Platform administrator needed a cross-tenant write the suite has no pattern for; the
  platform-side lever is now removing the Tenant's entitlement, with no new endpoint.
- Raw retention cannot be per Tenant while partitions are shared; it is a deployment setting, and a Tenant's earlier
  deletion is the erasure endpoint.
- Providers need one exact OAuth redirect URI but Tenants have different hosts; the callback is one URL on the Platform
  host and the Tenant comes from a signed `state`, recorded as the single exception to "tenant from the host".
- Google's OAuth scope for Ads is believed to allow writes as well as reads, so "read-only scopes" (a constraint in the
  advice) cannot be promised; the constraint is now "only read endpoints are called", enforced by a guard, with the scope
  stated plainly.
- The app role cannot create partitions; partition DDL goes through `SECURITY DEFINER` functions.
- A cross-tenant ingest batch cannot be inserted under one RLS setting; batches are grouped by tenant.
- Credits could not be recomputed after raw partitions are dropped; the touch chain is snapshotted on the conversion.
- The postback depends on the chain and credits, so it moved from the collection phase to the engine phase.
- Link-preview and chat unfurl bots hit redirects; they get the `302` and are stored as `BOT`.
- A chain across campaigns could credit one conversion twice; the owning-campaign rule fixes one model per conversion.

### Whole-Plan Consistency Sweep

All phase files re-read after the edits: dependency table, phase file names, sizes, the abuse lever, the retention scope,
the OAuth callback, the owning-campaign rule and the UTC-only non-goal agree across `plan.md` and phases 1-12.
