---
title: "Phase 8: Ops Seams, End-to-End and Docs"
status: todo
---

# Phase 8: Ops Seams, End-to-End and Docs

## Context Links

- `docker-compose.yml` — the mailer blocks to mirror (`x-mailer-environment`, `mailer-migrate`, `mailer-worker`)
- `ops/postgres/init/00-databases.sh:15-22,27-75` — one database, owner role, app role per service
- `ops/terraform/rds/init-databases.sh:24-77` — the managed variant of the same script
- `ops/terraform/{main,variables,locals,outputs}.tf`, `ops/terraform/{ecs/ecr.tf,secrets/secrets.tf,mq/}`
- `ops/terraform/platform/{alb.tf,service.tf,variables.tf}` — the HTTP-service shape (target group, listener rule)
- `.github/workflows/{platform.yml,core.yml,suite.yml,deploy.yml}`
- `ops/smoke/isolation.sh:46-104`, `ops/smoke/suite-smoke.sh`
- `ops/caddy/Caddyfile`
- `ops/keycloak/realm-export.json:62-82,114-138` — where `billing-audience` is declared

## Overview

Priority P1, size L. Turns a working service into a running one: a database with two roles, a broker user with exactly
two cross-service permissions, three compose services, a proxy route, a Terraform module with an API target group, CI
that actually builds it, and two smoke checks that prove the isolation and the end-to-end path.

It also lands the documentation a reader needs: what the app is, what it refuses, how to rebuild the replica, why a
notification was or was not emailed, and the runbook for a dead-lettered message.

## Key Insights

- **Every service is enumerated in more places than the compose file.** The database script, the Terraform RDS init,
  the secrets module, the ECR list, the log-group locals, the root module, the outputs, the broker users task, the
  `isolation.sh` matrices (flat, no loops), `deploy.yml`'s build/migrate/roll lists, and `suite.yml`'s `sed` credential
  rewrites. Missing one produces a stack that looks healthy and fails at the first message.
- `isolation.sh` has no loops: it is a flat list of `require_refused` calls. Today it holds 12 database refusals
  (4 roles × 3 foreign databases); a fifth service makes it 20, so this phase adds **8** lines, plus three control
  connects and the exchange assertions around the new broker user. A loop would be nicer, but rewriting the script is
  out of scope and would obscure the diff.
- The notification broker user is the **only** service user with a cross-service publish grant (`mailer.commands`).
  That deserves a positive control assertion, not just refusals: a script that only proves denials would keep passing
  if the grant vanished.
- The API needs a listener rule and a target group, and `platform/alb.tf` is the closest precedent: a path rule on
  `/notification/*` with its own priority. The proxy order matters in compose: the path rule must be evaluated before
  the catch-all that forwards to Billing.
- The realm export is import-only, so a running Keycloak needs the mapper added through the admin API; the export
  change is what makes a fresh environment correct.
- Mailer's plan left `ops/smoke/mailer-smoke.sh` and `.github/workflows/mailer.yml` unwritten. Repeating that omission
  for notification would leave the fourth app with no CI build, so this phase adds its workflow; repairing mailer's is
  recorded as a follow-up, not folded in here.

## Requirements

Functional

- `docker compose up -d --build --wait` brings up `notification-migrate`, `notification-api` and
  `notification-worker`, each healthy, with the API reachable at `<tenant>.localhost:8090/notification/...`.
- The database, roles and broker permissions exist in both the compose init and the Terraform RDS init.
- `ops/smoke/isolation.sh` covers the new service in the database matrix, the exchange matrix and the consume matrix,
  plus a positive control that it can publish to `mailer.commands`.
- A new smoke script proves the end-to-end path: grant access at Platform → one Mailpit message → one in-app row
  readable through the API.
- CI builds the service and runs its rule ids; deploy builds the image, runs the migrate task and rolls the two
  long-running services.

Non-functional

- No secret is committed; every password and the service-client hash come from the environment.
- The API task definition differs from the worker only by profile, environment and the target group attachment.
- The documentation explains the replica rebuild and the dispatch status vocabulary in one place.

## Architecture

```
Caddy: /notification/* ─strip─▶ notification-api:8080
AWS:   ALB /notification/* ─▶ target group ─▶ notification-api
       notification-worker ── (no listener, actuator probe only)
       notification-migrate ─ one-shot, run to completion before api/worker
broker: notification user
       read  platform.events, notification.platform-events(.dlq), suite.dlx
       write notification.platform-events(.dlq), suite.dlx, mailer.commands
```

## Related Code Files

Create

- `ops/terraform/notification/{variables,task-defs,services,alb,outputs}.tf`
- `.github/workflows/notification.yml`
- `ops/smoke/notification-smoke.sh`

Modify

- `docker-compose.yml` — `x-notification-environment`, `notification-migrate`, `notification-api` (port `8085:8080`),
  `notification-worker`; postgres env (`NOTIFICATION_MIGRATION_DB_PASSWORD`, `NOTIFICATION_DB_PASSWORD`); rabbitmq-users
  env (`NOTIFICATION_RABBITMQ_PASSWORD`); platform env (`PLATFORM_NOTIFICATION_SERVICE_CLIENT_ID`,
  `PLATFORM_NOTIFICATION_SERVICE_CLIENT_SECRET_HASH`); caddy `depends_on`
- `ops/postgres/init/00-databases.sh` — two `-v` args, owner and app roles, `create database notification`, revoke,
  grant, `\connect notification`, two `alter default privileges`
- `ops/terraform/rds/init-databases.sh` — the same blocks and the closing `echo` line
- `ops/terraform/secrets/secrets.tf` — the three passwords plus the service-client secret
- `ops/terraform/ecs/ecr.tf` — add `"notification"` to the repository set
- `ops/terraform/locals.tf` — log groups `notification` and `notification_migrate`
- `ops/terraform/variables.tf` — `notification_desired_count`, `notification_api_desired_count`
- `ops/terraform/main.tf` — the `module "notification"` block
- `ops/terraform/outputs.tf` — `task_definition_arns` and `service_names` entries
- `ops/terraform/mq/` — the notification broker user and its permissions
- `ops/rabbitmq/declare-users.sh` — the `declare_user notification` block
- `ops/smoke/isolation.sh` — 12 database refusals, three control connects, exchange refusals, one consume refusal, one
  positive publish control
- `ops/caddy/Caddyfile` — the `/notification/*` route before the catch-all
- `ops/keycloak/realm-export.json` — `notification-audience` mapper beside every `billing-audience`
- `.github/workflows/core.yml` — a "Notification rule ids" step in `rule-set`
- `.github/workflows/suite.yml` — `sed` rewrites for the new credentials
- `.github/workflows/deploy.yml` — build/push pair, repo output, migrate step, roll entry
- `docs/README.md` — index entry for the new doc
- `docs/runtime-profiles.md` — the three profiles and their commands
- `docs/mailer.md` — one cross-reference: who produces `email.send`
- `plans/261115-0307-mailer-service/plan.md` — note that its `mailer.yml` gap is still open, if the follow-up is not
  taken

## Implementation Steps

1. Compose. Add the env anchor and three services. `notification-migrate` is `restart: "no"`, `SPRING_PROFILES_ACTIVE:
   migrate`. `notification-api` maps `8085:8080`, depends on postgres, rabbitmq, rabbitmq-users, platform and the
   migrate step, and carries the same bash `/dev/tcp` readiness probe the other services use.
   `notification-worker` mirrors `mailer-worker` with profile `worker`. Mailpit is **not** a dependency: mailer owns
   SMTP, and a dead mail server must not stop notification.
2. Database init. Add the two roles, the database, the revoke from `public`, the grant to owner and app, and the two
   `alter default privileges` grants after `\connect notification`. Repeat in the Terraform RDS init script with
   `apply_default_privileges`.
3. Broker. Add the `declare_user notification` block with the read/write sets from the architecture diagram, keeping the
   double-escaped dots. Add the same user to the Terraform MQ users task.
4. Isolation. Add 8 `require_refused` database lines (each existing app against `notification`, and `notification_app`
   against each of the four existing databases — the new app's own cross-product), three control connects, the exchange
   refusals (`notification → platform.events`, `notification → billing.events`), the consume refusal (`billing` reading
   `notification.platform-events`), and the positive control: the notification user publishing to `mailer.commands` must
   **succeed**.
5. Terraform module. Write `ops/terraform/notification/`: `task-defs.tf` with three definitions (migrate, api, worker),
   `services.tf` with two services (api with `load_balancer` and `service_connect_configuration`, worker with neither),
   `alb.tf` with a target group and a `/notification/*` listener rule, `variables.tf`, `outputs.tf`. Wire the root
   module, locals, variables, outputs, ECR list and secrets.
6. CI. Write `.github/workflows/notification.yml` on the `platform.yml` shape with paths
   `notification/**, core/**, contracts/**, .github/workflows/notification.yml` and `./gradlew build`. Add the rule-id
   step to `core.yml`. Extend `suite.yml`'s credential rewrites and `deploy.yml`'s build, migrate and roll lists.
7. Proxy. Add the `/notification/*` handler with `uri strip_prefix /notification` and `reverse_proxy
   notification-api:8080`, placed before the default handler so the catch-all does not swallow it. Do the same as a
   listener rule on the ALB.
8. Keycloak. Add `notification-audience` (`included.custom.audience: notification`) beside each `billing-audience`
   mapper, and record in `docs/notification.md` that a running realm needs the mapper added through the admin API.
9. Smoke. Write `ops/smoke/notification-smoke.sh`: create or reuse a tenant with the `notification` entitlement enabled,
   grant a user access at Platform, wait for the replica, assert one message appears in Mailpit
   (`/api/v1/messages`) and one row is returned by `GET /notification/api/v1/notifications` with a user token, then
   mark it read and assert `unreadCount` drops to zero.
10. Docs. Write `docs/notification.md` covering: what the app is and is not, the three runtimes, the broker items, the
    five notification types and their audiences, the template parts and locale order, the dispatch status vocabulary,
    the replica rebuild procedure (delete the `replica_state` row, restart the worker), the dead-letter runbook, and the
    deliberate asymmetry (email skipped for a disabled user, in-app kept).
11. Run the whole suite and the two smoke scripts; fix what they find.

## Todo

- [ ] Compose anchor and three services
- [ ] Database and role creation in both init scripts
- [ ] Broker user in compose and Terraform, with the `mailer.commands` publish grant
- [ ] `isolation.sh`: 8 refusals, controls, exchange and consume assertions, positive publish control
- [ ] Terraform module plus every root wiring point
- [ ] CI workflow, rule-id step, suite and deploy lists
- [ ] Caddy route and ALB listener rule
- [ ] Keycloak audience mapper
- [ ] `notification-smoke.sh` passing against compose
- [ ] `docs/notification.md` plus index, profiles and mailer cross-reference
- [ ] Full `docker compose up -d --build --wait` with every service healthy

## Success Criteria

- `docker compose up -d --build --wait` reports every service healthy, including the three notification ones.
- `ops/smoke/isolation.sh` passes, and fails if the `mailer.commands` write grant is removed from the notification user.
- `ops/smoke/notification-smoke.sh` passes: one Mailpit message and one in-app row for one granted access.
- `cd ops/terraform && terraform fmt -check -recursive . && terraform init -backend=false && terraform validate` passes.
- `.github/workflows/notification.yml` runs `./gradlew build` on a `notification/**` change, and `core.yml` includes the
  notification rule-id step.
- `git diff --stat mailer/` is empty.
- `docs/notification.md` answers "why did this user get no email" with the four dispatch statuses and the two skip paths.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| A missed wiring point makes the stack green but inert | The checklist enumerates every enumeration site; the smoke script exercises the path through the proxy rather than inside the network |
| Caddy's catch-all swallows `/notification/*` | The route is placed before the default handler and proved by the smoke call, which goes through `:8090` |
| Terraform validate is unavailable locally | The mailer plan hit this too; the gate is CI's `terraform.yml`, which auto-discovers the new module, and `fmt`/`validate` are run where the binary exists |
| The second service client is provisioned with the wrong hash and the replica never builds | Readiness stays down and the log names the token request; the smoke script waits on readiness, so this fails visibly |
| The smoke script's Mailpit assertion competes with other messages | Assert the specific recipient address and subject, not the total count |
| Keycloak on a running deployment lacks the mapper | Documented as an admin-API step; a fresh compose imports the export |

## Security Considerations

- The new broker user has exactly one cross-service grant and it is asserted twice: as a refusal for the exchanges it
  must not write, and as a positive control for the one it must.
- Database roles follow the established split: owner migrates, app runs unprivileged with `nobypassrls`.
- Every credential is a compose environment placeholder or a Terraform-generated secret; nothing is committed.
- The service client secret is stored in Platform as a SHA-256 hash, and only the hash is configured.
- The task security group and subnet inputs are the shared ones; notification opens no new network path, and its API is
  reachable only through the proxy or the ALB.
- `docs/notification.md` states plainly that `SUCCEEDED` in mailer means accepted, not delivered.

## Next Steps

After this phase the plan's Done means are all observable. Follow-ups deliberately left out: an SSE stream, a template
editor UI, Billing-origin notifications, a mailer CI workflow, and hash-linking the migration files to the
`core-replica` DDL source.
