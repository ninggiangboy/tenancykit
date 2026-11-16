---
phase: 2
title: "Identity and ops seams"
status: pending
priority: P1
effort: "M"
dependencies: [1]
---

# Phase 2: Identity and Ops Seams

## Goal

Everything outside `booking/` that a new app needs is registered, so the build in phase 3 can start against a real database,
broker user, route and Platform entitlement. No Java outside `booking/` changes.

## Context

Re-run the touchpoint grep at execution time because other plans are editing the same files:
`git grep -l -i survey -- . ':!survey' ':!plans' ':!docs' ':!attribution' ':!crm' ':!loyalty'`. Known hits on 2026-11-16:
`.env.example`, `.github/workflows/{suite,survey}.yml`, `README.md`, `docker-compose.yml`, `ops/caddy/Caddyfile`,
`ops/keycloak/realm-export.json`, `ops/postgres/init/00-databases.sh`, `ops/rabbitmq/declare-users.sh`,
`ops/smoke/isolation.sh`, `platform/apps/app-boot/src/main/resources/application.yml`. Edits are append-only and re-read just
before each one, because survey, attribution, crm and loyalty are in flight.

## Files to edit

- `ops/postgres/init/00-databases.sh`: roles `booking_owner` (owner, runs migrations) and `booking_app` (no bypass of RLS),
  database `booking`, revoke public connect, grants as for survey. Password variables `BOOKING_MIGRATION_DB_PASSWORD`,
  `BOOKING_DB_PASSWORD`.
- `ops/rabbitmq/declare-users.sh`: user `booking` with `write` on its own queue, DLQ and `suite.dlx`, and `read` on
  `platform.events` only. No exchange of its own.
- `ops/caddy/Caddyfile`: `@bookingPath path /booking/*`, strip prefix, proxy to `booking-api:8080`; the public paths
  `/booking/p/*` need no credentials, as survey's do.
- `ops/keycloak/realm-export.json`: the audience mapping every app has, for `booking`.
- `platform/apps/app-boot/src/main/resources/application.yml`: one service-client entry `booking` with
  `${PLATFORM_BOOKING_SERVICE_CLIENT_ID:booking}` and secret hash, copied from survey's entry; compose passes the variables.
- `docker-compose.yml`: services `booking-migrate`, `booking-api`, `booking-worker`, env `BOOKING_*`, health checks, depends on
  Postgres, RabbitMQ and Platform, profile names as survey.
- `.env.example`, `README.md` (directory table and build line), `.github/workflows/booking.yml` (copy of `survey.yml`),
  `.github/workflows/suite.yml` (include the new workflow)
- `ops/smoke/isolation.sh`: add the booking user to the cross-exchange refusal checks

## Tasks

1. Verify, on a running stack, that a service-client entry is what lets an app's replica client authenticate (crm and loyalty
   planned the same check, because mailroom had no list entry); if booking's replica client cannot authenticate with the
   entry alone, stop and record what is missing in the journal.
2. Edit each file above, one at a time, re-reading it first; commit in small conventional commits.
3. Confirm that no variable is committed with a real value and that `.env.example` carries placeholders only.

## Verification

- `docker compose config` is valid; `docker compose up -d postgres rabbitmq` then `ops/rabbitmq/declare-users.sh` creates the
  `booking` user; the database `booking` exists and `booking_app` cannot connect to any other database.
- `ops/smoke/isolation.sh` still passes for every existing user.
- `git diff --stat` shows only the files above plus nothing under another app's source.

## Risks

- A concurrent plan rewrites a shared file between read and edit: re-read, reapply, never overwrite.
- Platform must be rebuilt after phase 1 before `booking` can be enabled for a Tenant, because the catalog is packaged at
  build time; the phase notes this in `docs/booking.md`.
