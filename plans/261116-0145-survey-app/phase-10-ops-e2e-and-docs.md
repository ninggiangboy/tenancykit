---
phase: 10
title: "Ops seams, end-to-end and docs"
status: pending
priority: P1
effort: "M"
dependencies: [8, 9]
---

# Phase 10: Ops Seams, End-to-End and Docs

## Goal

Survey runs in compose and the deployment pipeline, one end-to-end test proves the headline scenario against real
containers, and the docs describe what exists.

## Context

Follow the touchpoint list of `plans/261115-1849-email-app/phase-10-ops-e2e-and-docs.md`.

## Tasks

1. **Compose.** Services `survey-migrate`, `survey-api`, `survey-worker`; database `survey`
   (`ops/postgres/init/00-databases.sh`); broker user `survey` that may write `survey.events` only and read `survey.*`
   queues (`ops/rabbitmq/declare-users.sh`); `minio` with a bucket created at start and its S3 port reachable from the
   host for presigned URLs (`SURVEY_S3_PUBLIC_ENDPOINT`); the GeoIP file mounted read-only
   from `ops/geoip/` (downloaded by `ops/geoip/fetch.sh`, git-ignored, never committed).
2. **Proxy.** `ops/caddy/Caddyfile`: route `/survey/*` on the tenant host with the prefix stripped; `/survey/p/*` passes
   without credentials. The application still rejects an unauthenticated call to every non-`/p/` path.
3. **Keycloak.** A `survey-audience` mapper in `ops/keycloak/realm-export.json`, like the existing app mappers.
4. **Platform config.** One `service-clients` entry `survey` (`platform:replica:read`) in
   `platform/apps/app-boot/src/main/resources/application.yml` and its env vars in `.env.example`. No Java change.
5. **CI and deploy.** `.github/workflows/survey.yml`; entries in `suite.yml`, `core.yml`, `deploy.yml`; Terraform and
   Kubernetes resources following mailroom's (image, task definitions, secrets for the link key, DB password, S3
   credentials, broker user).
6. **Smoke.** `ops/smoke/isolation.sh` asserts `survey` can publish only to `survey.events`.
7. **End-to-end** (Testcontainers suite or `ops/smoke/` script): publish a survey with a branch and a quiz; create a
   public link for `qr-store-01` and 3 personal links; open, start and submit through both sources, abandon one,
   upload a file; assert aggregates, funnel by source, respondents, a CSV export, one `survey-response.submitted` per
   submit and one signed webhook per submit; a second tenant sees none of it.
8. **Docs.** Finish `docs/survey.md`: runtimes, contexts, data model, version and id rules, the expression language
   summary with a link to `contracts/survey/`, the public surface and its limits, the funnel's semantics and limits,
   deletion, files, GeoIP, rate limits, the dead-letter runbook, and the **manual resend recipe**: list
   `GET /surveys/{id}/respondents?state=COMPLETED`, add them to a mailroom group "responded-<survey>" through
   `POST /groups/{id}/members`, create a segment `in_group(invited) and not in_group(responded)`, run a campaign whose
   template variable carries the survey link. Update `docs/runtime-profiles.md`, `docs/architecture.md`,
   `docs/suite-messaging.md`, root `README.md`.
9. **Permission coverage.** A test asserts every code in `survey.yaml` is enforced by at least one endpoint and every
   endpoint requires one or is explicitly public.

## Verification

- `docker compose up` brings the suite up; `ops/smoke/isolation.sh` and the end-to-end suite pass.
- All builds green: `core`, `platform`, `billing`, mailroom, `notification`, `mailer`, `survey`, `tenant`.
- `git diff --stat` for the whole delivery shows no file under `platform/**/*.java`, mailroom or
  `SuiteActorResolver.java`.
- Back up any shared environment's databases before the first deploy migration runs.

## Risks

- `docs/survey.md` may exceed the 800-line limit; split the expression language and the resend recipe into
  `docs/survey-guide.md` if it does.
