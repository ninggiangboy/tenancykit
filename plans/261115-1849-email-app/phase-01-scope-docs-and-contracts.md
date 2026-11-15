---
phase: 1
title: "Scope, docs and contracts"
status: done
priority: P1
effort: "S"
dependencies: []
---

# Phase 1: Scope, Docs and Contracts

## Goal

Make the repository honest before any code exists: the docs currently forbid what the next nine phases build.

## Context

- `docs/overview-and-scope.md:104-114` — the non-goals "Notifying a Tenant's customers" and "bulk/marketing campaigns".
- `docs/notification.md` — "Notification … is not a bulk/marketing tool" and the "Four deployables" table.
- `contracts/permissions/notification.yaml` — the catalog format; the **file name is the app code**.
- `contracts/commands/email.send.schema.json`, `contracts/events/platform/*.schema.json` — contract style.
- `plans/261115-0619-notification-service/phase-01-scope-docs-and-contracts.md` — the precedent for this phase.

## Files to create / modify

- Modify: `docs/overview-and-scope.md` (reverse the two non-goals, extend the deployables table, state date and reason)
- Modify: `docs/notification.md` (one sentence: the Tenant's customers are served by `email/`, not here)
- Modify: `docs/architecture.md`, `docs/README.md`, `docs/suite-messaging.md`, `contracts/messaging/README.md` (topology rows)
- Create: `docs/email.md` (scope, what it is and is not, runtimes, status machines; filled further in phase 10)
- Create: `contracts/permissions/email.yaml`
- Create: `contracts/events/mailer/email-delivery.changed.schema.json`
- Modify: `contracts/commands/email.send.schema.json` (additive optional `headers`)
- Create: `contracts/openapi/email.yaml` (resource skeleton: paths, schemas; completed per phase)

## Tasks

1. Reverse the non-goals with the reason: the recipient is now the Tenant's customer, the data is the Tenant's own, and
   the suite stores it on the Tenant's behalf with retention and deletion rules. Keep the rationale of the old non-goal.
2. Write `email.yaml`. Codes (tenant scope; `grantable_to_tenant_system` true unless stated, because Tenant Systems are
   the primary callers and there is no UI):
   `email:template:read|manage`, `email:receiver:read|manage`, `email:email:send`, `email:email:read`,
   `email:campaign:read|manage`, `email:settings:manage` (**not** grantable to a Tenant System).
3. Write `email-delivery.changed.schema.json` (data only): `requestId`, `recipient`, `status` (`SUCCEEDED` | `DEAD`),
   `attempts`, `completedAt`, `providerMessageId` (optional), `lastError` (optional, short, no body). Document that
   `status` is an open set and a consumer ignores values it does not know.
4. Add `headers` to `email.send`: an object whose only allowed keys are `List-Unsubscribe` and `List-Unsubscribe-Post`,
   string values, no CR/LF, max length. Document why it is an allowlist.
5. Verify that no build globs `contracts/events/**` in a way that picks up the new `mailer/` directory. Billing's
   `SchemaEventTypeCatalog` reads `classpath*:events/*.schema.json` and fails on an unknown file, so the schema must not
   be packaged into Billing.
6. Update the topology tables: `email.platform-events`, `email.mailer-events`, the new exchange `mailer.events`, and the
   `email` producer of `email.send`.

## Verification

- `git grep -n "bulk/marketing" docs` returns only the dated reversal.
- Every code in `email.yaml` is used by an endpoint in a later phase (checked in phase 10).
- `./gradlew build` still passes in `billing/`, `platform/`, `notification/` and `mailer/` (contracts only changed additively).
- JSON schemas parse and validate a sample payload (a throwaway test in phase 2 consumes them).

## Risks

- A schema file in the wrong directory breaks Billing's build (task 5).
- Reversing a non-goal without recording why makes the history unreadable: the dated paragraph is mandatory.
