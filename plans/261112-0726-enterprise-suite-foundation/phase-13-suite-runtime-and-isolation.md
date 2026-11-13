---
phase: 13
title: "Suite runtime and isolation"
status: done
priority: P1
effort: "2.5d"
dependencies: [4, 5, 6, 7, 8, 12]
---

# Phase 13: Suite runtime, cross-database isolation and the smoke test

## Goal

`docker compose up` runs the whole suite (Platform, Billing, the sample tenant) behind one reverse proxy, every service in its own database with its own role and broker user, and a smoke script proves the Done-means end states. Gradle, ArchUnit and database-level checks block cross-database access.

## Key insights

- Today `docker-compose.yml` runs only infrastructure and the apps start with `bootRun`. Done means requires the apps in compose, so each service gets a Dockerfile; the infrastructure-only mode stays available by naming services (`docker compose up -d postgres rabbitmq keycloak`).
- One PostgreSQL server with one database per service (`platform`, `billing`, `tenant`), one owner role and one app role per database, and `revoke connect on database <db> from public`. That is real isolation at the database level and lighter than three servers.
- The reverse proxy only routes. Tenant resolution stays hostname-based, so the proxy must keep the `Host` header (Caddy does by default). Paths: `/platform/*` → Platform, everything else (`/api/*`, `/portal/*`, `/stripe/*`) → Billing, each prefix stripped before forwarding.

## Files

Create:
- `platform/apps/app-boot/Dockerfile`, `billing/apps/app-boot/Dockerfile`, `tenant/apps/app-boot/Dockerfile`: multi-stage, build context at the repo root (each build needs `../core`), `eclipse-temurin:25-jdk` build stage with a Gradle cache mount, `eclipse-temurin:25-jre` runtime, non-root user.
- `ops/postgres/init/00-databases.sql`: databases, roles, `revoke connect` (replaces the phase-4 `10-platform.sql`).
- `ops/rabbitmq/definitions.json`: one user per service with permissions limited to its exchanges and queues (configure, write, read regexes), loaded at boot.
- `ops/caddy/Caddyfile`: `*.localhost` and `platform.localhost` routes as above.
- `ops/smoke/suite-smoke.sh` (bash, `curl`, `jq`): the end-to-end checks listed under Verification; exits non-zero on the first failure with a clear message.
- `ops/smoke/isolation.sh`: tries `psql` as `billing_app` into `platform` and `tenant` (must fail), as `platform_app` into `billing` (must fail), and a broker publish by the `billing` user to `platform.events` (must be refused).
- `.github/workflows/suite.yml`: on push to `dev` and `workflow_dispatch`, `docker compose up -d --build --wait`, run both scripts, dump logs on failure.

Modify:
- `docker-compose.yml`: single `postgres` with the init folder; `rabbitmq` with definitions; `keycloak`; `caddy`; `platform`, `billing` (api and worker as two services, matching the existing runtime split), `tenant`; health checks and `depends_on: condition: service_healthy`; remove the separate `tenant-postgres`.
- `.env.example`: every variable the services need, with placeholder values only.
- Each service's ArchUnit rules: no class may reference another service's package root (`dev.ngb.platform`, `dev.ngb.billing`, `dev.ngb.tenant`).
- `platform/build.gradle.kts`, `billing/build.gradle.kts`: a `checkNoForeignServiceDependency` task like `tenant`'s `checkNoBillingDependency`, wired into `check`.
- `README.md`: how to run the suite and the smoke test.

## Smoke script steps (`ops/smoke/suite-smoke.sh`)

1. Get a platform admin token from Keycloak (dev-only direct-grant client in the realm export, disabled outside the compose profile).
2. `POST /platform/api/v1/tenants` creates tenant `acme` with an admin; enable app `billing`.
3. Poll until `GET http://acme.localhost/api/v1/customers` with the tenant admin's token answers 200 (Billing's replica has the tenant, domain, user and entitlement). Fail after 15 seconds; print the measured lag.
4. Create a tenant system and credential at Platform; get a token with `audience=billing`; call Billing with it (customer mapping).
5. Register the tenant app's webhook endpoint at Billing through the tenant app's bootstrap.
6. Create an invoice for a customer.
7. Assert: the tenant app recorded one verified `invoice.created` webhook (its admin read API or its log line).
8. Revoke a role from the admin at Platform; assert Billing answers 403 for an endpoint needing that permission within 5 seconds.

## Implementation steps

1. Dockerfiles; `docker compose build` succeeds.
2. Database and broker isolation files; `isolation.sh`.
3. Compose rewrite with health checks; `docker compose up -d --wait` brings every service healthy.
4. Caddy routing; verify Host preservation with a request to `acme.localhost`.
5. Smoke script; run it until green.
6. ArchUnit and Gradle foreign-service checks.
7. Suite workflow.

## Todo

- [x] Three Dockerfiles
- [x] Per-service databases, roles, broker users
- [x] Compose with every service, health checks, proxy
- [x] `suite-smoke.sh` and `isolation.sh` green locally
- [x] Foreign-service ArchUnit and Gradle checks in every build
- [x] `suite.yml` workflow

## Verification

- `docker compose up -d --build --wait && docker compose ps` shows every service healthy.
- `ops/smoke/suite-smoke.sh` and `ops/smoke/isolation.sh` exit 0.
- `./gradlew check` in each service build runs the foreign-service check.

## Risks

- Memory on a laptop (about six containers plus the worker processes). Mitigation: `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50` and container memory limits of 512 MB per JVM.
- Direct-grant client in Keycloak is a dev convenience that must not reach a real deployment. Mitigation: it is only in the compose realm file and documented as such.

## Security

- No secret in committed files; `.env.example` holds placeholders only.
- Per-service broker users: a service can publish only to its own exchange and read only its own queues.
