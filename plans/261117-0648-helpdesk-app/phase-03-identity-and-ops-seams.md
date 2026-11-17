---
phase: 3
title: "Identity and ops seams"
status: pending
priority: P1
effort: "M"
dependencies: [1]
---

# Phase 3: Identity and Ops Seams

## Goal

Everything outside `helpdesk/` that a new app needs is registered, so phase 4 can start against a real database, broker user,
route, object store and Platform entitlement. No Java outside `helpdesk/` changes.

## Context

Re-run the touchpoint grep at execution time because other plans edit the same files:
`git grep -l -i booking -- . ':!booking' ':!plans' ':!docs' ':!attribution' ':!customer' ':!loyalty' ':!survey' ':!workflow'`.
Known hits on 2026-11-17: `.env.example`, `.github/workflows/{booking,suite}.yml`, `README.md`, `docker-compose.yml`,
`ops/caddy/Caddyfile`, `ops/keycloak/realm-export.json`, `ops/postgres/init/00-databases.sh`, `ops/rabbitmq/declare-users.sh`,
`ops/smoke/isolation.sh`, `platform/apps/app-boot/src/main/resources/application.yml`. Edits are append-only and re-read before
each one.

## Files to edit

- `ops/postgres/init/00-databases.sh`: roles `helpdesk_owner` (runs migrations) and `helpdesk_app` (no RLS bypass), database
  `helpdesk`, grants as for survey; variables `HELPDESK_MIGRATION_DB_PASSWORD`, `HELPDESK_DB_PASSWORD`.
- `ops/rabbitmq/declare-users.sh`: user `helpdesk` with the grants of mailroom's line (its own `platform-events` and
  `mailer-events` queues with their DLQs, `suite.dlx`, write on `mailer.commands`, read on `platform.events` and `mailer.events`).
  No exchange of its own.
- `ops/caddy/Caddyfile`: `/helpdesk/*` to `helpdesk-api:8080`; `/helpdesk/p/*` and `/helpdesk/inbound/*` need no user credentials
  (the inbound path additionally accepts only the adapter's network in a deployment, see phase 17).
- `ops/keycloak/realm-export.json`: the audience mapping for `helpdesk`.
- `platform/apps/app-boot/src/main/resources/application.yml`: one service-client entry `helpdesk`, copied from booking's.
- `docker-compose.yml`: `helpdesk-migrate`, `helpdesk-api`, `helpdesk-worker`; env `HELPDESK_*` including the object store (the
  existing MinIO and a bucket `helpdesk`), the inbound signing keys, the thread token keys; health checks and dependencies as
  booking.
- `.env.example` (placeholders only), `README.md` (directory table, build line), `.github/workflows/helpdesk.yml` (copy of
  `booking.yml`), `.github/workflows/suite.yml`.
- `ops/smoke/isolation.sh`: add the helpdesk broker user to the refusal checks (it may write `mailer.commands`, nothing else
  outside its own queues).

## Tasks

1. Verify on a running stack that a service-client entry is what lets an app's replica client authenticate (booking planned the
   same check); if it does not, stop and record what is missing in the journal.
2. Edit each file one at a time, re-reading it first; small conventional commits.
3. Create the `helpdesk` bucket by appending to the existing `minio-setup` service (it creates only survey's bucket today, and
   the MinIO root credentials are named `SURVEY_S3_*`); reuse the same MinIO instance and give helpdesk its own access key
   variables `HELPDESK_S3_*` so the app never holds survey's credentials. Re-read `docker-compose.yml` first: it is shared.
4. Confirm no variable carries a real value and `.env.example` holds placeholders.
5. Note in `docs/helpdesk.md` that Platform must be rebuilt after phase 1's permission file lands before a Tenant can enable
   `helpdesk`, because the catalog is packaged at build time.

## Verification

- `docker compose config` is valid; `docker compose up -d postgres rabbitmq minio` followed by `ops/rabbitmq/declare-users.sh`
  creates the `helpdesk` user; the `helpdesk` database exists and `helpdesk_app` cannot connect to any other database.
- `ops/smoke/isolation.sh` passes for every existing user and shows the helpdesk user refused on every exchange except
  `mailer.commands` writes.
- `git diff --stat` shows only the files above and nothing under another app's source.

## Risks

- A concurrent plan rewrites a shared file between read and edit: re-read, reapply, never overwrite.
- The inbound path being unauthenticated at the proxy is deliberate (the request is signed); phase 15 proves a missing or bad
  signature is refused, and phase 17 restricts the network in a deployment.
