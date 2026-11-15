---
phase: 10
title: "Ops seams, end-to-end and docs"
status: done
priority: P1
effort: "M"
dependencies: [8, 9]
---

# Phase 10: Ops Seams, End-to-End and Docs

## Goal

The new app runs in compose and in the deployment pipeline, one end-to-end test proves the headline scenario against real
containers, and the docs describe what exists.

## Context

`b3472cbf` and `0ddb2343` list every ops file a new service touched: `.env.example`, `.github/workflows/{core,deploy,suite,<app>}.yml`,
`docker-compose.yml`, `ops/caddy/Caddyfile`, `ops/keycloak/realm-export.json`, `ops/postgres/init/00-databases.sh`,
`ops/rabbitmq/declare-users.sh`, `ops/smoke/isolation.sh`, `ops/terraform/**`, `docs/runtime-profiles.md`.

## Tasks

1. **Compose**: services `mailroom-migrate`, `mailroom-api`, `mailroom-worker`; database `mailroom`; broker user `mailroom` that may
   write `mailer.commands` only and read `mailroom.*` queues; healthchecks like notification's.
2. **Proxy**: route `/mailroom/*` on the tenant host; the public tracking paths `/mailroom/t/*` forward without auth and with
   a per-IP rate limit. Keep Caddy routing on the Host header and path prefix only.
3. **Keycloak**: a `mailroom-audience` mapper (see the existing `notification-audience` mapper in `ops/keycloak/realm-export.json`).
4. **Platform data**: an entitlement `mailroom` per tenant is created through the Platform API, not by code. The catalog file
   from phase 1 registers the app code.
5. **CI**: `.github/workflows/mailroom.yml`, entries in `suite.yml`, `core.yml`, `deploy.yml`.
6. **Terraform / Kubernetes**: follow the notification service's resources (ECR, task definitions, secrets for the HMAC key
   and DB password, broker user). Add the tracking-token key to the secret list; document rotation.
7. **Smoke**: `ops/smoke/isolation.sh` asserts `email` cannot publish to `platform.events`, `billing.events`,
   `mailer.events`, and can publish to `mailer.commands`; `mailer` can publish `mailer.events` and nothing else.
8. **End-to-end test** (`ops/smoke/` script or a Testcontainers suite): create template and group, import receivers,
   run a campaign through a real mailer (SMTP to Mailpit), assert Mailpit received N messages with the rewritten links and
   `List-Unsubscribe` header, fetch a link and the pixel, post an unsubscribe, assert `stats` and that a second campaign
   skips the unsubscribed receiver. Add a second tenant and assert it sees none of it.
9. **Docs**: finish `docs/mailroom.md` (runtimes, broker items, status machines, tracking semantics and their limits,
   retention, the suppression rules, the dead-letter runbook), update `docs/runtime-profiles.md`, `docs/architecture.md`,
   `docs/suite-messaging.md`, `docs/mailer.md`, root `README.md`; add a `docs/email-tenant-guide.md` only if `email.md`
   exceeds the 800-line doc limit.
10. **Permission coverage**: a test asserts every code in `mailroom.yaml` is enforced by at least one endpoint and every
    endpoint requires one (or is explicitly public).

## Verification

- `docker compose up` brings the suite up; `ops/smoke/isolation.sh` and the end-to-end script pass.
- All builds green in CI: `core`, `platform`, `billing`, `notification`, `mailer`, `email`, `tenant`.
- The two non-goal bullets in `docs/overview-and-scope.md` are gone and replaced by the dated reversal; `git grep` for
  "publishes nothing" in `docs/` returns nothing.
- Back up any shared environment's databases before the first deploy migration runs.

## Risks

- The proxy is the only place the public/private split is expressed twice (Caddy and the security chain); a test asserts the
  application rejects an unauthenticated call to every non-tracking path even if the proxy mis-routes.
