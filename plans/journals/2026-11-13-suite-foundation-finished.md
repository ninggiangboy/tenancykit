---
title: Suite foundation finished — tenant tokens, the running suite, and two defects it exposed
date: 2026-11-13
summary: "Phases 12-14 of the enterprise suite foundation: the sample tenant on Platform tokens, the whole suite in compose behind one proxy with isolation checks, and the docs"
---

# Suite foundation finished

## What happened

Implemented phases 12, 13 and 14 of `plans/261112-0726-enterprise-suite-foundation`, the last open work in that
plan. Phases 1-8 had already moved tenancy, users and access into Platform; these finish the picture: the sample
tenant authenticates the way the plan intends, the whole suite runs from one `docker compose up`, and the docs
describe it.

Booting the suite for real was the point. Two defects in the shipped code only appear end to end, and both are fixed
here.

## What changed

**Phase 12 — the sample tenant on suite tokens**

- `PlatformTokenProvider` exchanges the tenant's Platform client credentials at `POST /oauth/token`
  (`audience=billing`), caches the token until 60 seconds before expiry, shares one refresh across concurrent callers
  and refreshes once on a 401. `TokenClient` (a Feign `Client`) attaches the token and performs the one retry.
- The static API key is gone: `BearerAuthInterceptor`, `HttpBillingSettings.apiKey` and the
  `tenant.billing.api-key` / `tenant-system-id` settings are removed. `NewCustomer` and `byExternalUser` dropped their
  `tenantSystemId`, because Billing derives the system from the token.
- `checkNoBillingDependency` also rejects Platform and the service-only core modules (`core-messaging`,
  `core-replica`), and an ArchUnit rule rejects any reference to another service's package.
- `PlatformTokenProviderTest` covers the cache, the pre-expiry refresh, the shared refresh and the invalidate; the
  Feign test covers the 401 retry and the second refusal.

**Phase 13 — the running suite**

- Three multi-stage Dockerfiles (build context at the repo root, because each build includes `../core`).
- One PostgreSQL with a database, owner role and app role per service and `revoke connect` from public; one RabbitMQ
  user per service, limited by permission regexes; a Caddy that routes on Host only; a `billing-migrate` step and
  health-gated compose; `suite.yml` runs the whole stack in CI.
- `ops/smoke/suite-smoke.sh` drives the Done-means end states; `ops/smoke/isolation.sh` proves the database and broker
  walls. Both pass against a running stack.
- `checkNoForeignServiceDependency` in the Platform and Billing builds, plus the ArchUnit foreign-service rule in
  each, so a build fails when one service reaches into another at compile time.

**Phase 14 — docs**

- Rewrote the owning section of each doc for the suite model, created `docs/platform-service.md` and
  `docs/suite-messaging.md`, marked the original specification as the historical baseline, and reconciled the old
  plan's phases 11-14. Every relative link resolves and both stale-claim greps are clean.

## The two defects end-to-end found

- **The inbox foreign key blocked every new tenant.** Billing's `event_inbox.tenant_id` referenced `tenants`, but a
  new tenant's first event *is* `tenant.changed`, which creates that row. The insert failed, the whole delivery
  rolled back, and a tenant created after Billing started never reached the replica. `V25__inbox_without_tenant_fk.sql`
  drops the constraint; the inbox is keyed by the event's tenant id, and row-level security still isolates it, which
  is exactly what `core-messaging`'s schema contract says it should be.
- **The suite-token decoder defaulted to RS256.** `SuiteJwtDecoders` built its Platform `NimbusJwtDecoder` with no
  algorithm, so it defaulted to RS256 and refused every ES256 suite token with "no matching key(s)". A fresh Billing
  rejected even a token whose signature verified against the published JWKS. The decoder now names ES256 for Platform
  and RS256 for the Keycloak realm, and `SuiteJwtDecodersTest` proves an ES256 token decodes against a real JWKS
  served over HTTP — a test that fails on the old code.

## Lessons

- A suite that "builds and unit-tests green" can still have never delivered one message. The smoke test caught both
  defects in minutes; neither had a unit test that could have, because each needs three services, a broker and a
  real token in flight.
- Forged snapshots and JWKS are cache-shaped: an ephemeral signing key that changes on restart silently invalidates
  what a consumer already verified against. `PLATFORM_TOKEN_SIGNING_KEY` exists for that; the compose profile may run
  ephemeral, but a restart of Platform alone then requires restarting consumers too.
- A bootstrap that checks for a row and then creates a Keycloak identity is not idempotent against a retained
  Keycloak when the earlier Platform row was rolled back: recreating Platform fails with "user already exists". Clean
  volumes sidestep it; making the bootstrap re-adopt an existing identity is the real fix and is noted for later.

## Verification

- `./gradlew build` green in `core/`, `platform/`, `billing/` and `tenant/`; `checkNoBillingDependency` and
  `checkNoForeignServiceDependency` green.
- `docker compose up -d --build --wait` brings every service healthy; `ops/smoke/isolation.sh` and
  `ops/smoke/suite-smoke.sh` both exit 0 (replica caught up ≤2s, revocation latency <5s).
- `git grep -n "api-key|SAMPLE_TENANT_BILLING_API_KEY" tenant` and the two phase-14 stale-claim greps return nothing;
  no doc exceeds 800 lines; every relative link resolves.

## Deferred

- The Suite Console UI (a separate plan).
- Making the Platform bootstrap re-adopt an existing Keycloak identity so a recreate against retained Keycloak data
  starts cleanly.
