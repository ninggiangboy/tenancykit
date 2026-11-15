---
title: Renamed the email app to mailroom
date: 2026-11-16
summary: "email/ is now mailroom/ end to end: directory, root package, jOOQ package, DB and roles, broker user and queues, permission prefix, token audience, public /mailroom prefix, contracts, docs, compose, Terraform and the remaining plan phases"
---

# Renamed the email app to mailroom

## Outcome

`email/` became `mailroom/` before any of it was deployed, so nothing that already holds real data had to be migrated.
The metaphor: *mailroom* writes, addresses and dispatches the mail; `mailer` is the carrier that delivers it. The two
names share no substring, so a `grep` for one no longer matches the other.

Everything below was renamed, because each was the **app's identity** rather than the business word "email":

- Directory `email/` → `mailroom/`, `rootProject.name`, the build-logic build name, the Dockerfile, the CI workflow.
- Root package `dev.ngb.email` → `dev.ngb.mailroom`; jOOQ package `dev.ngb.email_jooq` → `dev.ngb.mailroom_jooq`
  (regenerated with `-Djooq.update=true`, never hand-edited).
- Three classes that carried the app's name: `EmailPermissions` → `MailroomPermissions`, `EmailSettings` →
  `MailroomSettings` (the config record, not the `email_settings` table), `EmailTransactionScopeHook` →
  `MailroomTransactionScopeHook`.
- Config: `spring.application.name`, `suite.app-code`, the token `audiences`, every `EMAIL_*` variable, the
  `email.*` configuration tree (now `mailroom.*`), the DB and both role names, the broker user.
- Queues `mailroom.platform-events`, `mailroom.mailer-events` and both `.dlq`, in code, compose, the broker script and
  the isolation smoke.
- Permission codes `mailroom:<resource>:<action>`, and therefore `contracts/permissions/mailroom.yaml`,
  `contracts/openapi/mailroom.yaml`, the Keycloak audience mapper and the Platform-facing entitlement key.
- Docs `docs/mailroom.md` plus the seven documents that named the app; the plan's remaining phases and the survey plan's
  precondition.
- Ops: compose services and environment block, Caddy route, Keycloak mapper, PostgreSQL init, RabbitMQ users, the
  Terraform module and every task family, log group, secret, ECR repo and RDS database.

## The one decision the brief left open

The public prefix moved from `/email/*` to `/mailroom/*` (confirmed with the user), so `EMAIL_PUBLIC_BASE_URL`,
the Caddy route, the ALB path pattern, `PublicTrackingController`'s alias mapping and the contract paths all changed.
`OpenApiContractTest` strips that prefix before comparing, and both its `substring` lengths had to change with it — a
silent break otherwise, because the test would have stripped six characters from a nine-character prefix.

## What deliberately kept the word "email"

The business noun, not the app: `email.send`, `email-delivery.changed`, `mailer/contexts/email`, the `/emails`
resource, the `email_templates` / `email_settings` / `emails` / `email_events` / `email_links` tables and their jOOQ
names, every `receiver.email` field, `"format": "email"`, and Keycloak's own `email` client scope. No global
`sed s/email/mailroom/g` was ever run; every replacement was a targeted pattern.

## A pre-existing gap, not fixed here

`platform/apps/app-boot/src/main/resources/application.yml` registers service clients for `billing` and `notification`
only. There has never been a `mailroom` (previously `email`) entry, so the replica never authenticates in a deployed
stack even though mailroom is configured with a client id and secret. This predates the rename and is out of its scope;
it needs its own change, with its own hash and its own secret.

## Verification

- `cd mailroom && ./gradlew build --rerun-tasks` is green: 165 tests, including the ArchUnit and Modulith gates, the
  jOOQ drift test (after regeneration), the per-table RLS test, the contract/path coverage test and the permission
  catalog test against the renamed YAML files.
- `notification` and `mailer` still build green, which is the check that the shared `contracts/` tree did not move
  under them.
- `ops/smoke/isolation.sh`, `ops/rabbitmq/declare-users.sh`, `ops/postgres/init/00-databases.sh` and the RDS init
  script all pass `bash -n`; the realm export parses as JSON; `docker compose --env-file .env.example config` succeeds.
- Not executed here: `docker compose up` and `ops/smoke/isolation.sh` end to end, `terraform` (not installed), and the
  GitHub workflows.
