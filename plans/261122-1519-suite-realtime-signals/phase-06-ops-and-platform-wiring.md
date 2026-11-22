---
phase: 6
title: "Ops and Platform wiring"
status: pending
priority: P1
effort: "1.5d"
dependencies: [3]
---

# Phase 6: Ops and Platform wiring

## Goal

The local suite, CI, Caddy, the broker, Postgres and Platform all know about realtime, so the service runs beside the
others with its own database, its own broker user and its own Platform service client.

## Context

Notification shows every touchpoint: `docker-compose.yml` (`notification-migrate`, `notification-api`,
`notification-worker`, an env anchor), `ops/postgres/init/00-databases.sh`, `ops/rabbitmq/declare-users.sh`,
`ops/caddy/Caddyfile` (`/notification/*` prefix and the platform-host ordering rule), `.env.example`,
`ops/env/generate-env.sh`, `.github/workflows/notification.yml`, `ops/terraform/notification/`, and a Platform
`platform.service-clients` entry plus a Keycloak `notification-audience` mapper.

## Requirements

1. Compose: `realtime-migrate`, `realtime-api`, `realtime-worker` with an env anchor; no Redis.
2. Postgres: database `realtime`, roles `realtime_owner` and `realtime_app` with the same default privileges.
3. Broker user `realtime`: configure/write on its own queues, `realtime.fanout` and `suite.dlx`; read on
   `realtime.commands`, `platform.events`, its own queues and `realtime.fanout` (a queue bind checks read on the source).
   Per-replica queue names follow one pattern covered by the regex.
4. Every producer's broker user gains `write` on `realtime.commands` only (their `read` sets are unchanged); this edit is
   made in `declare-users.sh` and applied in the producer phases (7, 8, 9), but the regex shape is fixed here.
5. Caddy: route `/realtime/*` with the prefix stripped, `flush_interval -1`, and the same platform-host ordering as
   notification (a `@realtimePath` handler before the platform-host rule).
6. Platform: a service client `realtime` (own secret, only the SHA-256 in Platform's configuration, scope
   `platform:replica:read`), and the Keycloak audience mapper for `realtime`.
7. CI: `.github/workflows/realtime.yml` modelled on `notification.yml` (Java 25, Testcontainers, same path filters plus
   `contracts/**`); extend `suite.yml` where it lists services.
8. Terraform: `ops/terraform/realtime/` following `ops/terraform/notification/` (service, secrets, discovery), including
   the ALB timeout suitable for long streams.
9. Env: new variables in `.env.example` and `ops/env/generate-env.sh`; `Makefile` run target and `README.md` port table.

## Files

Modify: `docker-compose.yml`, `ops/postgres/init/00-databases.sh`, `ops/rabbitmq/declare-users.sh`,
`ops/caddy/Caddyfile`, `.env.example`, `ops/env/generate-env.sh`, `Makefile`, `README.md`, `.github/workflows/suite.yml`,
Platform's service-client configuration and Keycloak realm provisioning notes (`ops/keycloak/realm-export.json` only fixes
fresh environments; a running realm needs the mapper added through the admin API and that goes in the runbook).

Create: `.github/workflows/realtime.yml`, `ops/terraform/realtime/*`.

## Steps

1. Add the compose services and env anchor by copying the notification block and removing what realtime does not use
   (no mailer settings); choose a free host port.
2. Add the database and roles in `00-databases.sh`, then the broker user block with a comment explaining each grant, as
   the existing blocks do.
3. Add the Caddy route and prove ordering with a request to `platform.localhost/realtime/...` reaching realtime.
4. Register the Platform service client: generate a secret, store its SHA-256 in Platform's list, document the entry.
5. Add the CI workflow and the Terraform module.
6. Bring the stack up: `docker compose up -d --build --wait`; confirm `realtime-migrate` exits 0, api and worker become
   healthy and the worker finishes one replica rebuild.

## Todo

- [ ] Compose services and env
- [ ] Database and roles
- [ ] Broker user and its grants
- [ ] Caddy route with flush interval
- [ ] Platform service client and Keycloak mapper (runbook for running realms)
- [ ] CI workflow and Terraform module
- [ ] Stack comes up healthy locally

## Verification

- `docker compose up -d --build --wait` exits 0 with `realtime-api` and `realtime-worker` healthy.
- `curl -i http://localhost:8080/realtime/actuator/health/readiness` (with the right Host) returns UP after the rebuild.
- `docker compose config --services` lists no `redis`.

## Risks

- Port and host ordering mistakes in Caddy silently route stream calls to Platform; the proof request in step 3 guards it.
- An ALB or proxy idle timeout shorter than the heartbeat interval kills streams; align both values and note them.

## Security

- The secret and its hash live in `.env` and Platform configuration, never in the repository.
- The broker user's grants are the minimum listed above; phase 13 proves the refusals.

## Next

Phases 7, 8, 9 (each also edits `declare-users.sh` for its producer's `realtime.commands` write grant).
