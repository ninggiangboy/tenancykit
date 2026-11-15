---
phase: 5
title: "Local Ops and Mailpit"
status: completed
priority: P1
effort: "M"
dependencies: [4]
---

# Phase 5: Local Ops and Mailpit

## Goal

Make `docker compose up` start mailer's migrate and worker runtimes alongside a Mailpit inbox, and make the
isolation smoke script prove mailer cannot reach another service's database or exchange.

## Key Insights

- Mailpit does not exist in compose today. `axllent/mailpit:v1.33.1` serves SMTP on 1025 and HTTP API/UI on
  8025; it has **no** authentication and **no** STARTTLS, which matters for JavaMail (see the risk table).
- Billing is the only service that splits `migrate` / `api` / `worker`
  (`docker-compose.yml:176-254`). Mailer copies the `migrate` + `worker` pair: no `api` at all.
- Every new database needs roles created outside the service. Local:
  `ops/postgres/init/00-databases.sh` (owner + app role, `create database`, `revoke connect … from public`,
  grants, `alter default privileges`). This script runs only on an empty data directory, so an existing
  volume needs `docker compose down -v` or a manual `psql`.
- Broker permissions are declared in `ops/rabbitmq/declare-users.sh` and are regex-scoped per user. A
  consumer needs: `configure` on its exchange, queue and DLQ plus `suite.dlx`; `write` on its queue and DLQ
  (queue.declare with a dead-letter exchange and queue.bind both check write on the queue); `read` on its
  exchange and queue and `suite.dlx`. Billing's block at `:56-66` is the template.
- `.github/workflows/suite.yml:15-40` builds `.env` from `.env.example` with a chain of `sed` replacements.
  Every new placeholder that uses `${VAR:?set it in .env}` must get a line there or CI fails before compose
  starts.
- `ops/smoke/isolation.sh` pairs every app role against every other database and asserts refusal. Mailer
  adds its own positive check and six negative ones.

## Files to Create / Modify

- Modify: `docker-compose.yml` — add `mailpit`, `mailer-migrate`, `mailer-worker`; extend postgres and
  `rabbitmq-users` environment passthrough
- Create: `mailer/apps/app-boot/Dockerfile` — **based on `billing/apps/app-boot/Dockerfile`, not the
  tenant's**. Billing's copies `contracts/` (`:12`); the tenant's does not (`:8-9`), and mailer's
  `processResources` packages `contracts/commands`, so a tenant-shaped Dockerfile produces an image that
  builds green and then crash-loops on a missing schema resource.
- Create: `mailer/apps/app-boot/src/main/resources/application.yml`
- Create: `mailer/apps/app-boot/src/main/resources/application-migrate.yml` — web none, flyway on, jobs off
- Create: `mailer/apps/app-boot/src/main/resources/application-worker.yml` — flyway off, listener on, jobs on,
  and `management.health.mail.enabled: false`
- Modify: `ops/postgres/init/00-databases.sh` — `mailer_owner`, `mailer_app`, database, grants, default privileges
- Modify: `ops/rabbitmq/declare-users.sh` — `declare_user mailer …`
- Modify: `.env.example` — `MAILER_MIGRATION_DB_PASSWORD`, `MAILER_DB_PASSWORD`,
  `MAILER_RABBITMQ_PASSWORD`, `MAILER_SECRETS_MASTER_KEY`
- Modify: `.github/workflows/suite.yml:15-40` — one `sed` line per new placeholder
- Modify: `ops/smoke/isolation.sh` — mailer in the refusal matrix plus its own-database check
- Modify: `docs/runtime-profiles.md` — mailer's two processes in the process table
- Modify: `README.md` — `mailer/` row in the directory table

## Architecture

Three new compose services, in dependency order:

```yaml
mailpit:          image axllent/mailpit:v1.33.1, ports 1025/8025
mailer-migrate:   SPRING_PROFILES_ACTIVE=migrate, runs once, exits 0
mailer-worker:    SPRING_PROFILES_ACTIVE=worker, depends_on mailer-migrate:
                    condition: service_completed_successfully
                  plus postgres and rabbitmq healthy;
                  mailpit only `service_started`
```

`mailpit` deliberately uses `service_started`, not `service_healthy`. Two reasons: the published image is a
static binary with no shell and no `curl`, so a container-side HTTP probe can never pass; and more
importantly, gating the worker on SMTP availability contradicts the design — the ledger's backoff is what
should handle a dead SMTP server. A hard dependency converts a transient outage into "the worker never
starts". Publish no `healthcheck` for mailpit in compose and poll `/api/v1/info` from the host in the
verification step instead.

Mailer is not routed by Caddy: it serves no HTTP business endpoint. Its only HTTP surface in compose is the
actuator probe and, for developers, the Mailpit UI.

Broker permission block to add. **Every dot is double-escaped** because the regex is pasted into a JSON
document by `declare-users.sh:37` (`api PUT "permissions/%2F/$1" "{\"configure\":\"$3\",…}"`); a single `\.`
is not a legal JSON escape, the management API answers 400, `curl --fail-with-body` exits non-zero and
`set -eu` aborts the one-shot `rabbitmq-users` container, so the whole stack never becomes healthy. Compare
against `declare-users.sh:62-66` line for line:

```sh
declare_user mailer \
  "${MAILER_RABBITMQ_PASSWORD:?set the mailer broker password}" \
  '^(mailer\\.commands|mailer\\.commands\\.dlq|suite\\.dlx)$' \
  '^(mailer\\.commands|mailer\\.commands\\.dlq|suite\\.dlx)$' \
  '^(mailer\\.commands|mailer\\.commands\\.dlq|suite\\.dlx)$'
```

`write` must include `suite\\.dlx` and the DLQ, because `QueueBuilder.deadLetterExchange` plus the DLQ bind
both check write on the queue (`core/core-messaging/.../topology/SuiteTopology.java:37-51`). `read` must
include the exchange because `queue.bind` checks read on the source exchange.

Note the residual privilege: any consumer needs `read` on `suite.dlx` to declare its dead-letter pair, and
that exchange carries every service's dead letters. Mailer is not a special case — Billing has the same
grant — but `isolation.sh` only tests *publish* refusal, so add one consume assertion: mailer must be
refused when attempting to bind a queue to `suite.dlx` with a routing key outside its own queue name.

## Implementation Steps

1. Add the `mailpit` service with its healthcheck, and add `mailer-migrate` and `mailer-worker` modelled on
   `billing-migrate` / `billing-worker` (`docker-compose.yml:176-196`, `:229-254`).
2. Write the three `application*.yml` files. `application-worker.yml` carries the listener and job switches;
   `application-migrate.yml` sets `spring.main.web-application-type: none`, `flyway.enabled: true` and
   `mailer.jobs.enabled: false`.
3. Add the mailer database and roles to `ops/postgres/init/00-databases.sh`, following the existing block
   shape exactly: two roles, database owned by the owner, `revoke connect, temporary from public`, then
   `grant connect` to owner and app, and `alter default privileges` for the owner.
4. Pass both passwords from `docker-compose.yml`'s postgres environment.
5. Add the broker user and pass `MAILER_RABBITMQ_PASSWORD` through the `rabbitmq-users` service.
6. Add the three `.env.example` entries and the matching `sed` lines in `suite.yml`.
7. Extend `isolation.sh`: `mailer_app` must be refused against `billing`, `platform` and `tenant` databases,
   each of those app roles must be refused against `mailer`, and `mailer_app` must reach its own.
8. Write the Dockerfile with the repository root as context, copying `mailer/` and `core/`.

## Todo

- [x] `mailpit` service (ports 1025/8025, no container healthcheck)
- [x] `mailer-migrate` and `mailer-worker` compose services
- [x] `application.yml`, `application-migrate.yml`, `application-worker.yml` (with `management.health.mail.enabled: false`)
- [x] Dockerfile based on Billing's, copying `contracts/`
- [x] `00-databases.sh`: `mailer_owner`, `mailer_app`, grants, default privileges
- [x] `declare-users.sh`: double-escaped mailer regex including `suite.dlx`
- [x] Four `.env.example` entries + matching `suite.yml` sed lines
- [x] `isolation.sh`: refusal matrix plus one consume-refusal assertion
- [x] `docs/runtime-profiles.md` and `README.md` updated

## Verification

```bash
cp .env.example .env   # fill values
docker compose up -d --build --wait
docker compose ps                       # mailer-migrate exited 0, mailer-worker healthy, mailpit healthy
ops/smoke/isolation.sh                  # must pass, mailer included
curl -s localhost:8025/api/v1/info | jq .
```

Also prove the broker scoping, which the smoke script covers but is worth checking by hand once:

```bash
docker compose exec -T rabbitmq rabbitmqadmin -u mailer -p "$MAILER_RABBITMQ_PASSWORD" \
  publish exchange=billing.events routing_key=x payload=test  # must be refused
```

## Risk Assessment

| Risk | Signal it broke | Response |
|---|---|---|
| `mail.smtp.auth=true` with no credentials | `AuthenticationFailedException` against Mailpit | Leave `spring.mail.username`/`password` unset; Mailpit needs neither |
| `mail.smtp.starttls.required=true` | Handshake failure against Mailpit | Do not set it; Mailpit offers STARTTLS only when a cert is configured |
| Postgres init script not re-run on an existing volume | `mailer_app` does not exist, migrations fail | `docker compose down -v`, or run the role DDL manually; record this in the README |
| A `.env.example` placeholder without a `suite.yml` sed line | CI fails at compose with "set it in .env" | Add the line in the same commit |
| Mailer given `read` on `billing.events` by copy-paste | Isolation smoke fails | Mailer reads only its own exchange, queue, DLQ and `suite.dlx` |
| Broker regex single-escaped | `rabbitmq-users` exits 1, `docker compose up --wait` never completes | Double every backslash; diff against `declare-users.sh:62-66` |
| Tenant's Dockerfile used as the template | Image builds green, worker crash-loops on a missing `commands/` resource, and only in ECS | Copy `contracts/` into the image; base the file on Billing's |
| `spring.mail.host` set re-enables the mail health contributor | `/actuator/health` reports `DOWN` during every SMTP outage and the supervisor restarts the worker | Set `management.health.mail.enabled: false` in the worker profile |
| `MAILER_SECRETS_MASTER_KEY` missing from `.env.example` | Phase 9's cipher refuses to start; compose cannot boot the worker | Add it with the other three and give it a `sed` line in `suite.yml` |

## Security Considerations

- Mailpit has no auth and is bound to the host for developer use. It must never be added to the AWS path.
- The mailer broker user must not be able to publish to `platform.events` or `billing.events`; the isolation
  script is the proof, not the intent.
- `MAILER_DB_PASSWORD` is the unprivileged app role; only the migration profile gets the owner password.

## Next Steps

Phase 6 implements the consumer that this phase's wiring turns on.

## Verification Note

The compose file, the database roles, the broker regex, the `.env.example` entries, the `suite.yml` sed lines and the
`isolation.sh` matrix are all written and reviewed. The script itself was **not executed**: it needs the whole stack
running, which is not available in the environment this phase was completed in. The two things to re-check by hand on
the first `docker compose up` are the broker regex (one wrong backslash aborts `rabbitmq-users`, and the stack then
never becomes healthy) and `mailer_app`'s connect grant.
