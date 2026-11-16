---
phase: 2
title: "Identity and broker seams for both apps"
status: done
priority: P1
effort: "M"
dependencies: [1]
---

# Phase 2: Identity and Broker Seams for Both Apps

## Goal

Everything outside the two app trees that a service needs before it can start is in place for both apps (databases and roles,
broker users, Platform service clients, Keycloak audience mappers, environment names), so the skeleton phases can run and test
against the compose infrastructure, and the open question about mailroom's service client is answered with evidence.

## Context

The out-of-tree touchpoints of a new app come from
`git grep -l -i mailroom -- . ':!mailroom' ':!plans' ':!docs' ':!attribution' ':!survey'` (re-run it now; it listed
`.env.example`, `.github/workflows/*`, `README.md`, `contracts/*`, `docker-compose.yml`, `ops/caddy/Caddyfile`,
`ops/keycloak/realm-export.json`, `ops/postgres/init/00-databases.sh`, `ops/rabbitmq/declare-users.sh`,
`ops/smoke/isolation.sh`, `ops/terraform/**`). This phase takes only the identity and broker part of that list; compose app
services, Caddy, CI, Terraform and smoke tests belong to phase 16. Survey and attribution are being edited in parallel: every
edit here is append-only.

## Files to modify

- `platform/apps/app-boot/src/main/resources/application.yml` (two `service-clients` entries, no Java)
- `.env.example`, `docker-compose.yml` (Platform environment and the two databases' variables only)
- `ops/postgres/init/00-databases.sh`, `ops/rabbitmq/declare-users.sh`, `ops/keycloak/realm-export.json`

## Tasks

1. **Verify the mailroom question first.** `docker compose up -d postgres rabbitmq keycloak platform`, then request
   `POST /oauth/token` as client `mailroom` with the secret from `.env`. Record the status in the phase notes. If it is `401`,
   mailroom's entry is missing from Platform's list: stop, report it, and ask whether to add it in this phase. Do not change
   mailroom's behaviour on your own.
2. **Platform service clients.** Add `crm` and `loyalty` entries (scope `platform:replica:read`, own secret hash each) in the
   style of lines 67-74 of `application.yml`, with `PLATFORM_CRM_SERVICE_CLIENT_*` and `PLATFORM_LOYALTY_SERVICE_CLIENT_*`
   variables in `.env.example` and the Platform service block of `docker-compose.yml`. No Java change.
3. **Databases.** In `00-databases.sh` add roles `crm_owner`, `crm_app`, `loyalty_owner`, `loyalty_app` and databases `crm`
   and `loyalty` exactly like the mailroom block (owner migrates, app role owns nothing, no `bypassrls`, `public` revoked,
   default privileges), with `CRM_*` and `LOYALTY_*` password variables.
4. **Broker users** in `declare-users.sh`. Both apps are replica consumers only and own no exchange. `crm`: write its own
   queue, DLQ and `suite.dlx`; read `platform.events` and its own queue. `loyalty`: the same with its own names. Queue names:
   `crm.platform-events`, `loyalty.platform-events`. Neither user gets `read` on `billing.events`, `survey.events`,
   `mailer.*` or the other's queue, and no `crm.events` exchange exists: crm's facts leave as webhooks (phase 3).
5. **Keycloak.** `crm-audience` and `loyalty-audience` mappers beside the existing `*-audience` mappers.
6. **Names.** Put the future environment variable names (`CRM_*`, `LOYALTY_*`) in `.env.example` with comments, so phases 3 and
   4 only consume them.
7. **Platform catalog.** Platform learns its apps from `contracts/permissions/*.yaml` at build time
   (`platform/apps/app-boot/build.gradle.kts:51-56`, `ClasspathAppCatalog`). Rebuild and restart Platform so `crm` and
   `loyalty` appear as apps, and check that an entitlement for each can be enabled for a test Tenant through the Platform API.

## Verification

- `docker compose up -d` for the infrastructure services is healthy; the broker one-shot finishes; `psql` as `crm_app` can
  connect to `crm` and not to `loyalty` or `mailroom`; `loyalty` cannot publish to `platform.events` and cannot bind a queue
  to `billing.events` (the isolation script in phase 16 repeats this; here a manual `rabbitmqadmin` or management-API call is
  enough).
- A token request for client `crm` and for `loyalty` returns `200` with scope `platform:replica:read` and audience `platform`.
- `git diff --stat` shows only the files above, and `git diff` for `application.yml` shows two added list entries and nothing
  else.
- Back up any shared environment's databases before running the init script against it.

## Risks

- The init script only runs on a fresh volume; document `docker compose down -v` for a local stack that already exists.
- Editing shared files while other plans edit them: keep the diff minimal, commit this phase quickly and rebase before
  touching them again.
