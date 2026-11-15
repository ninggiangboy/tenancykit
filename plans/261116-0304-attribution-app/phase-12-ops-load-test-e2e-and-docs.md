---
phase: 12
title: "Ops seams, load test, end-to-end, privacy and docs"
status: pending
priority: P1
effort: "L"
dependencies: [11]
---

# Phase 12: Ops Seams, Load Test, End-to-End, Privacy and Docs

## Goal

Attribution runs in compose and the deployment pipeline, the redirect meets its measured target, one end-to-end test proves
the headline scenario against real containers, and the docs describe what exists, including what the app cannot promise.

## Context

The files a new service touches outside its own tree, found with
`git grep -l -i mailroom -- . ':!mailroom' ':!plans' ':!docs'` on 2026-11-16: `.env.example`,
`.github/workflows/{core,deploy,suite,mailroom}.yml`, `README.md`, `contracts/README.md`, `contracts/messaging/README.md`,
`docker-compose.yml`, `ops/caddy/Caddyfile`, `ops/keycloak/realm-export.json`, `ops/postgres/init/00-databases.sh`,
`ops/rabbitmq/declare-users.sh`, `ops/smoke/isolation.sh`, `ops/terraform/{README.md,main.tf,locals.tf,variables.tf,
outputs.tf,ecs/ecr.tf,mq/users.tf,mq/variables.tf,rds/databases-roles.tf,rds/init-databases.sh,secrets/secrets.tf,
mailroom/*}`. There are no Kubernetes manifests in the repository for mailroom.

## Tasks

1. **Compose.** Services `attribution-migrate`, `attribution-api`, `attribution-worker`; database `attribution`
   (`ops/postgres/init/00-databases.sh`); broker user `attribution` (`ops/rabbitmq/declare-users.sh`) that may write the
   exchange `attribution.ingest`, its queues and DLQs and `suite.dlx`, and read `platform.events` and its own queues; the
   GeoIP file mounted read-only from `ops/geoip/` (fetched by `ops/geoip/fetch.sh`, git-ignored, never committed; reuse the
   script if survey's phase already added it).
2. **Proxy.** `ops/caddy/Caddyfile`: `/go/*` to `attribution-api` with the prefix kept; `/attribution/*` with the prefix
   stripped, on every host (so the Platform host serves the one OAuth callback); both before the platform-host rule and
   Billing's catch-all. No logic in the proxy.
3. **Keycloak.** An `attribution-audience` mapper in `ops/keycloak/realm-export.json`, like the existing app mappers.
4. **Platform config.** One `service-clients` entry `attribution` (`platform:replica:read`) in
   `platform/apps/app-boot/src/main/resources/application.yml` and its variables in `.env.example`. No Java change.
5. **CI and deploy.** `.github/workflows/attribution.yml`; entries in `suite.yml`, `core.yml`, `deploy.yml`; Terraform
   `ops/terraform/attribution/` and the shared files above following mailroom's (image repository, task definitions for
   api, worker and migrate, ALB rules for `/go/*` and `/attribution/*`, secrets: click, OAuth-state and visitor-hash keys,
   master key, DB password, broker user, provider client ids and secrets).
6. **Smoke.** `ops/smoke/isolation.sh`: `attribution` can publish to `attribution.ingest` and nothing else
   (`platform.events`, `billing.events`, `mailer.commands`, `mailer.events`, `survey.events` refused);
   `attribution_app` cannot connect to the other databases and no other app role can connect to `attribution`.
7. **Load test.** A `loadTest` Gradle task in `attribution/apps/app-boot` (outside the normal build, as Billing's) and
   `ops/loadtest/attribution.sh`: 500 requests per second for 60 s against `/go/{slug}` across 50 links on the compose stack;
   asserts p95 and p99 against `docs/attribution.md`, zero 5xx, and, after draining, that rows stored equal events
   published. A second run stops the broker for 20 s and asserts every redirect still answered.
8. **End-to-end** (Testcontainers suite or `ops/smoke/` script): create a campaign with `LAST` and a second with `LINEAR`, a
   link with two destinations and one personal batch of 3; click through both; load a page with the snippet with consent
   and without; post a conversion and a snippet goal; add manual cost; assert reports, funnel, cohort and cost metrics to the
   cent; change the model and assert the recompute; erase a visitor and assert; a second tenant sees none of it.
9. **Privacy and abuse runbooks** in `docs/attribution.md`: what is stored and for how long; the consent flag is the Tenant's
   duty; erasure steps; how to take down an abusive link (remove the Tenant's `attribution` entitlement in Platform, which
   makes every `/go/*` of that Tenant return `404` once the replica applies it, or archive the link as the Tenant);
   dead-letter runbook; partition and retention checks; GeoIP attribution (DB-IP Lite, CC BY 4.0).
10. **Docs.** Finish `docs/attribution.md` (runtimes, contexts, data model, click and token rules, identity and consent,
    models and the owning-campaign rule with an example, limits of each metric: bot filtering is heuristic, uniques are
    per day, snippet counts are lower bounds, UTC days, cost day boundaries, provider facts and versions). Update
    `docs/runtime-profiles.md`, `docs/architecture.md`, `docs/suite-messaging.md`, `docs/overview-and-scope.md`, root
    `README.md`, `contracts/README.md`, `contracts/messaging/README.md`. If `docs/attribution.md` passes 800 lines, split the
    Tenant guide into `docs/attribution-guide.md`.
11. **Permission coverage.** A test asserts every code in `attribution.yaml` is enforced by at least one endpoint and every
    endpoint requires one or is explicitly public.

## Verification

- `docker compose up` brings the suite up; `ops/smoke/isolation.sh`, the load test and the end-to-end suite pass.
- All builds green: `core`, `platform`, `billing`, `mailroom`, `notification`, `mailer`, `attribution`, `tenant` (and `survey`
  if it exists).
- `git diff --stat` for the whole delivery shows no file under `platform/**/*.java`, `billing/`, `mailroom/` or
  `SuiteActorResolver.java`.
- `terraform validate` passes in `ops/terraform`.
- Back up any shared environment's databases before the first deploy migration runs.

## Risks

- The load numbers are measured on a laptop's compose stack: report them with the hardware, not as a capacity claim.
- The proxy is the only place the public and private split is expressed twice (Caddy and the security chain): a test
  asserts the app rejects an unauthenticated call to every non-public path even if the proxy mis-routes.
