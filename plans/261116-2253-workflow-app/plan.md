---
title: "Workflow app — a configurable Tenant System that runs versioned JSON workflows across the suite and external systems"
description: "An API-only workflow/ deployable: Tenants define versioned JSON-graph workflows; a self-written durable engine on Postgres + RabbitMQ runs them (delay, wait-for-event, human task, parallel, for-each, sub-workflow, saga compensation) and calls suite apps with a dedicated Platform credential and external systems through guarded connections. It is the Tenant side of the suite, not an app-to-app link."
status: in-progress
priority: P1
effort: "15 phases, 4 × M, 11 × L"
branch: dev
tags: [feature, backend, api, database, infra, security]
blockedBy: []
blocks: []
created: 2026-11-16
---

# Workflow App

## Overview

Every flow that spans suite apps (react to a failed payment, nudge expiring points, approve a refund, erase a customer
everywhere) is code the Tenant's own system must write today, because suite apps never call each other. This plan adds
`workflow/`, a configurable Tenant System: the Tenant describes the flow as JSON, the engine runs it durably and performs the
calls the Tenant's system would have made, with the Tenant's own credential, and may also call systems outside the suite.

The suite rule (2026-11-16, "apps never link, the Tenant System is the only integrator") is **kept**: workflow acts as a Tenant
System, authenticates with a Tenant-owned credential, reaches apps only by their public API under the Tenant's hostname, and
never binds to any app's broker exchange; its triggers are the signed webhooks the apps already send. Phase 1 writes this into
the shared contracts and docs.

Scope was fixed in the `/ak-advise` interview of 2026-11-16 (memory: `workflow-app-decisions`). The user chose the heavy
route on purpose: all four capability groups in v1 and a self-written durable core. Their cost is in the risk register.

## Outcome contract

**Goals**

1. A Tenant describes a workflow as a versioned JSON graph through the API, publishes it, and sees it run from trigger to end.
2. The engine survives restarts: delays, waits, tasks and retries resume, and no step is lost or recorded twice.
3. Workflows call Billing, mailroom, CRM, loyalty and any other suite app as the Tenant, and external systems through
   Tenant-owned connections, with no code written by the Tenant.
4. People take part: a human task is assigned to a user or role, has a due time and an escalation, and ends with a decision.
5. A failure is visible and recoverable: retries, dead letter, replay from the failed step, and saga compensation.
6. The engine is a safe place to hold credentials: a Tenant's data is isolated, a credential is revocable at once, a
   connection cannot reach internal addresses, and no secret is ever written to history or logs.

**Done means**

- `workflow/` builds, migrates and tests green; ArchUnit and Modulith gates pass; api, worker and migrate runtimes start.
- The four sample workflows run end to end through `ops/smoke/workflow.sh` playing the Tenant System on the compose stack:
  payment dunning, refund approval (ends in a real Billing refund), points-expiring nudge, erasure fan-out.
- Crash safety (confirmed wording, see Validation Log): stopping the worker with `SIGKILL` while an instance waits in a delay,
  and again while a step is mid-call, then restarting, completes the instance; every step has exactly one completion record,
  and every send of one step carries the same `Idempotency-Key`, so a key-honouring target shows one effect.
- Property and crash tests for the core pass (two workers claiming one token, a timer firing twice, an event arriving before
  its wait, an event and its timeout racing).
- Tenant A cannot read or change tenant B's definitions, instances, tasks, connections or history on any table or endpoint
  (row-level security test per table plus an API probe).
- Revoking the workflow credential in Platform makes every later call from the engine fail as `credential_invalid` within one
  token lifetime, and disabling the Tenant System stops it at once; a connection to `localhost`, a private address or a
  redirect to one is refused.
- `git grep` finds no secret value, in tests, logs or history rows; step history shows redacted requests and responses.
- `contracts/openapi/workflow.yaml`, `contracts/permissions/workflow.yaml`, `contracts/events/workflow/*.schema.json` and
  `contracts/workflow/` (graph schema, expression schema, vectors) exist and contract tests prove the running app matches them.
- `docs/workflow.md`, `docs/suite-messaging.md`, `docs/overview-and-scope.md` and `contracts/messaging/README.md` state the
  suite rule with workflow as the Tenant side; no change under `survey/`, `crm/`, `loyalty/`, `mailroom/`, `attribution/`,
  `booking/`, `mailer/`, `tenant/` Java, apart from the two deliberate changes below.

**Deliberate changes outside `workflow/`** (user decisions of 2026-11-16, all outside the earlier "no other app changes" habit)

- Platform gets `purpose` on a Tenant System (`GENERIC` or `WORKFLOW`) with a grant rule (phase 2).
- Platform creates the Tenant's `workflow` Tenant System (purpose `WORKFLOW`, no credential) when the `workflow` entitlement is
  enabled, idempotently, and disables it when the entitlement is disabled (phase 2). Credentials stay a Tenant decision.
- `billing:refund:create` becomes `grantable_to_tenant_system: true` so a workflow can refund (phase 2). This opens the refund
  permission to any Tenant System credential a Tenant chooses to grant it to; the cost is in the risk register.

**Non-goals**

Visual builder or any UI; BPMN or any imported format; Tenant-written code or scripts; a connector marketplace or typed
per-app connectors (an action is a generic HTTP call checked against the app's published OpenAPI); flow analytics or
dashboards; notifications for human tasks (a workflow calls mailroom itself); billing the Tenant for workflow use; any
app-to-app link; Temporal or any external engine; changing any other app's code; backfill of past events into waits;
sharing code with Survey (the expression evaluator is copied, never imported, and Survey is never edited).

**Constraints**

Tenant always from the host and the token, never from client input; API only; definitions are immutable once published and an
instance is pinned to the version it started with; the core runs on Postgres (source of truth) and RabbitMQ (wake-up only,
losing a message costs latency, never correctness); every outward call is signed or authenticated by the Tenant's own
credential; calls to apps use the Tenant's hostname because apps resolve the tenant from the host alone; connection secrets
and the Platform credential are encrypted at rest with a key outside the database and are write-only through the API; existing
suite patterns (jOOQ with committed generated schema, RLS, suite tokens, ETag and `If-Match`, `core/` modules, outbox and
inbox, api/worker/migrate runtimes); conventional commits without AI references; no plan or phase number in code, migration
or test names.

## Evidence from the scout (verified in source on 2026-11-16)

| Finding | Source | Consequence |
|---|---|---|
| An app builds the tenant scope from the hostname alone and ignores any tenant id from the client; the host maps through the replicated `tenant_domains` | `core/core-suite/src/main/java/dev/ngb/core/suite/web/SuiteTenantFilter.java`, `SuiteHostResolver.java` | The engine replicates Platform data too and calls each app with the Tenant's hostname (URL or `Host` header); calling an internal address without it answers 404 |
| A Tenant System token carries `tenant_id`, one audience, ten minutes of life, only scopes valid for that audience; revoking a credential 401s the next request, disabling the system is refused at once | `contracts/tokens/README.md` | The engine mints one token per audience, caches it until about a minute before `exp`, and treats 401 as `credential_invalid` |
| A Tenant System can hold several credentials (rotate, at most two live) with a scope subset of codes marked `grantable_to_tenant_system`; the system has no kind or purpose field | `platform/.../ManageCredentialUseCase.java`, `TenantSystemScopes.java`, `git grep -n "kind\|purpose" -- platform/.../TenantSystem.java` is empty | Phase 2 adds `purpose` (user decision) |
| `billing:refund:create` is `grantable_to_tenant_system: false`; the Tenant System OpenAPI only lists refunds, never creates one | `contracts/permissions/billing.yaml:103-106`, `contracts/openapi/billing-tenant-system.yaml` | Phase 2 flips the flag (user decision) and verifies Billing's refund endpoint serves a Tenant System principal with no Java change; if it does not, stop and ask |
| Billing's Tenant System API has `POST /api/v1/invoices/{id}/payments` and `POST /api/v1/subscriptions/{id}/cancel` but no pause and no retry-payment; Billing already retries on its own schedule and reports `subscription.payment_failed` with `willRetry` | `contracts/openapi/billing-tenant-system.yaml:3167,5127`, `contracts/events/billing/subscription.payment_failed.schema.json` | The dunning sample waits for `willRetry=false`, then pays the invoice and cancels |
| Survey has no erasure endpoint; CRM and loyalty have `/api/v1/customers/{externalRef}/erasure` and `/api/v1/members/{externalRef}/erasure`; mailroom has `/api/v1/emails` (`mailroom:email:send`, grantable) | `contracts/openapi/{crm,loyalty,mailroom}.yaml` | The erasure sample fans out to CRM, loyalty, mailroom (receiver removal verified in phase 15) and one external system |
| `core-webhook` already has a destination policy, a DNS guard that rejects private addresses, a signer, an HTTP sender and a retry schedule | `core/core-webhook/src/main/java/dev/ngb/core/webhook/{DestinationPolicy,GuardedDnsResolver,WebhookHttpSender,WebhookSigner,RetrySchedule}.java` | The connection SSRF guard is reuse, not new code |
| `core-webhook` defines header sets for Billing and Survey only; CRM and loyalty headers are not in `core` | `core/core-webhook/.../WebhookHeaderSet.java`, `core/core-kernel/.../HttpHeaders.java` | Phase 7 reads crm's and loyalty's webhook contexts and `contracts/events/*/README.md` before writing the verifier |
| Survey's `Evaluator` is pure Java but its references (`answer`, `row`) are survey-specific, and survey is in flight | `survey/contexts/authoring/src/domain/java/dev/ngb/survey/authoring/domain/expression/Evaluator.java`, `contracts/survey/README.md` | Copy the semantics into workflow with its own references; never edit or import Survey |
| `Idempotency-Key` is documented in the Billing, CRM, loyalty and mailroom OpenAPI files | `grep -il idempot contracts/openapi/*.yaml` | A stable per-step key makes at-least-once sends safe for every suite target |
| crm is committed and final (three feature commits); attribution and survey have uncommitted work; a booking plan is pending | `git log -- crm`, `git status` | Copy the shape of `mailroom/` and crm's committed `webhook` context; every edit to shared ops files is append-only |

## Design summary (what the phases build)

- **Graph.** Nodes: `trigger`, `http`, `branch`, `delay`, `wait`, `task`, `parallel` and `join`, `foreach`, `subflow`, `end`,
  `fail`. Edges: `next`, `on_error`, and a per-node `compensate` action. Definitions are JSON checked by a schema and a
  structural checker (reachable, every parallel has its join, no unbounded cycle, size limits).
- **Execution.** A token walks the graph. A worker claims a READY token with `FOR UPDATE SKIP LOCKED` and a lease, runs the
  node outside the transaction when it must call out, then one transaction records the step and creates the next tokens. A
  sweeper re-queues expired leases. RabbitMQ `workflow.runnable` only wakes workers sooner.
- **Time.** Timers (delay, wait timeout, task due, retry backoff) are rows with `due_at`, swept with `SKIP LOCKED`. A wait
  and its timeout race through one compare-and-set on the token, so exactly one wins. Inbound events are kept for a window so
  an event that arrives before its wait is not lost.
- **Calls.** An `http` node names an app plus method and path, checked at publish against that app's OpenAPI; the engine adds
  the Tenant's hostname, a token for the app's audience, and the stable `Idempotency-Key`. External calls use a connection.
- **Security.** Secrets are AES-256-GCM encrypted with an environment key and a key id; history stores redacted copies.

## Phases

| # | Phase | Effort | Status |
|---|---|---|---|
| 1 | [Scope, contracts and docs](phase-01-scope-contracts-and-docs.md) | M | done |
| 2 | [Identity seams, Platform purpose and refund grant](phase-02-identity-seams-platform-purpose-and-refund-grant.md) | M | done |
| 3 | [Build skeleton, replica and gates](phase-03-build-skeleton-replica-and-gates.md) | M | done |
| 4 | [Expression language and vectors](phase-04-expression-language-and-vectors.md) | M | done |
| 5 | [Definition authoring and publish](phase-05-definition-authoring-and-publish.md) | M | done |
| 6 | [Execution core](phase-06-execution-core.md) | M | done |
| 7 | [Triggers: inbound webhook, manual start, schedule](phase-07-triggers.md) | M | done |
| 8 | [Durable time: delay and wait-for-event](phase-08-durable-time.md) | M | done |
| 9 | [HTTP actions, connections and credential vault](phase-09-http-actions-connections-and-vault.md) | M | done |
| 10 | [Control flow: branch, parallel, for-each, sub-workflow](phase-10-control-flow.md) | L | done |
| 11 | [Human tasks](phase-11-human-tasks.md) | L | done |
| 12 | [Failure handling, compensation and replay](phase-12-failure-compensation-and-replay.md) | L | done |
| 13 | [Workflow webhooks and event contract](phase-13-workflow-webhooks-and-events.md) | M | done |
| 14 | [History, quotas, retention and runtimes](phase-14-history-quotas-retention-and-runtimes.md) | M | done |
| 15 | [Sample workflows, chaos, isolation, ops and docs](phase-15-samples-chaos-isolation-ops-and-docs.md) | M | done |

Order: 1 → 2 → 3 → 4 → 5 → 6 → {7, 8, 9} → 10 → 11 → 12 → 13 → 14 → 15. Phases 7, 8 and 9 touch different contexts and may
run in parallel after phase 6. Phase 2's Platform and Billing edits can land before the skeleton exists. Milestones: after
phase 6 the core is demonstrable by tests; after phase 9 dunning without waits runs; after phase 12 all four capability groups
exist; phase 15 is the finish line.

## Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| The self-written core has a concurrency bug (double claim, lost timer, wait/timeout race) | High | High | Core tested before any node exists (phase 6); a crash-and-duplicate harness runs in CI; every state change is one conditional `UPDATE`; Postgres is the only truth |
| The engine holds Tenant credentials, so a bug exposes several apps at once | Med | High | Dedicated credential with scope subset and `purpose=WORKFLOW`; write-only encrypted secrets; redacted history; per-Tenant RLS; secret scan test; revoke and disable proven in tests |
| A connection reaches internal services (SSRF) | Med | High | Reuse `DestinationPolicy` and `GuardedDnsResolver`; refuse redirects to private ranges; response size and time caps; test with `localhost`, `169.254.169.254`, a DNS name resolving to `10.x`, and a redirect |
| `billing:refund:create` on Tenant System credentials widens refund power | Med | High | Only a Tenant admin can grant it to a credential, and the credential is the dedicated workflow one; document it as an opt-in; Billing's own limits and audit stay; revisit if a refund amount cap is wanted (not built) |
| One Tenant floods the engine (loops, fan-out, huge payloads) | Med | High | Hard caps in phase 14 (concurrent instances, steps per instance, iterations, branches, payload, history age) enforced in the engine, with `429`/failed-instance reasons |
| At-least-once sends cause a duplicate effect in a target without idempotency | Med | Med | Stable `Idempotency-Key` always sent; docs tell Tenants which targets honour it; external connections may opt out of retries on non-idempotent calls |
| Scope is large (15 phases, all four capability groups) | High | Med | Milestones above; each is a safe stopping point; cost accepted by the user |
| Platform replica lacks role membership (verified: it holds effective permissions per user only) | Certain | Low | Tasks are assigned to a user or a workflow-owned task group (resolution 8); role-based targeting is a later extension |
| Auto-created `workflow` Tenant System is mistaken for a ready integration | Low | Low | It has no credential until the Tenant issues one; `GET /platform-credential` reports `NOT_REGISTERED`; docs list the three steps |
| In-flight work on survey, attribution and booking conflicts with shared ops files | Med | Low | Edit shared files append-only; re-grep touchpoints at execution time |

## Red-team pass (single adversarial self-review; no separate subagent run)

- **Assumptions.** "RabbitMQ wake-up only" assumes the DB sweep is cheap enough; the sweeper polls an indexed `available_at`
  predicate and is bounded. "Apps accept the Tenant hostname from the engine" holds only if the public gateway routes the
  host; phase 9 tests through Caddy, not only direct.
- **Failure.** Worker dies between send and record: the same key is sent again (accepted, documented). Event arrives twice:
  deduplicated by the source event id. Timer and event race: one CAS. Platform down: token cache covers up to ten minutes,
  then steps retry with backoff and the instance stays running.
- **Scope.** Human-task completion by a Tenant System (not only a user) is confirmed by the user (resolution 5); task groups
  (resolution 8) are the one small addition forced by what Platform replicates. Nothing else beyond the confirmed scope is added.
- **Security.** Replay of a signed inbound webhook is bounded by the timestamp window and event-id dedup; a workflow author
  must not be able to read secrets (write-only connections, secrets never in expressions); expression evaluation has no I/O,
  clock or network and size limits; `http` targets for suite apps are restricted to configured app hosts.

## Validation Log

Confirmed with the user on 2026-11-16 while planning:

1. **Credential kind.** Add `purpose` (`GENERIC`, `WORKFLOW`) to Platform's Tenant System, with a grant rule. Rejected: a plain
   dedicated Tenant System with no Platform change. Consequence: new Platform Java, a migration, and a replica event field.
2. **Refund sample.** Make `billing:refund:create` grantable to a Tenant System so the sample ends in a real Billing refund.
   Rejected: ending with a call to the Tenant's own endpoint. Consequence: a security-relevant catalog change.
3. **Delivery guarantee.** At least once with a fixed `Idempotency-Key`, one completion record per step. Not "exactly once".
4. **Expressions.** Copy Survey's semantics into workflow; do not extract to `core/`, do not edit Survey.

Resolved by the user on 2026-11-16 after planning:

5. **Who completes a human task.** A user who is the assignee or belongs to the task's group, or a Tenant System with
   `workflow:task:complete` that names the person (recorded as stated by the system, not verified). Both allowed.
6. **Early-event buffer.** User had no preference; default kept: 7 days, configurable per Tenant up to 30.
7. **First `workflow` Tenant System.** Platform creates it when the `workflow` entitlement is enabled for a Tenant. Consequence:
   a third deliberate change in Platform (see below). The credential is still issued by the Tenant, because its secret is shown
   once and its scopes are the Tenant's choice.
8. **Task assignment targets (user left it to me).** The scout found Platform replicates each user's *effective permission
   codes*, not role membership (`contracts/events/platform/user-access.changed.schema.json`: "consumers never join roles
   themselves"). So "assign to a role" cannot be resolved from the replica. Decision: a task is assigned to a **user id** or to
   a **task group**, a named list of user ids that workflow owns and a Tenant manages through the API; members must be
   ACTIVE users in the replica, and acting on a task still requires the `workflow:task:complete` permission. Rejected for now:
   replicating role membership from Platform (a new event and replica table). Cost: the Tenant maintains groups in workflow
   instead of reusing Platform roles; a later phase may add role-based targeting without changing the node shape.

## Reconciliation (2026-11-17)

Every phase's code, contracts, migrations and tests are in the tree, and `cd workflow && ./gradlew check` is green.
The Done-means claims proved by the build: the app builds, migrates and tests green (including ArchUnit and the Modulith
gate); the four sample graphs are publishable (`SampleWorkflowsTest`); property and crash behaviour is proved by
`ExecutionCoreTest`, `JoinStateConcurrencyTest` and `LoopStateConcurrencyTest` (two workers, lease takeover, concurrent
join and loop arrivals); tenant isolation is proved per table by `RowLevelSecurityTest`; revocation, the SSRF list and
the secret scan are proved by the connections context's tests and the copied guard suites; the contracts are generated
from the running app (`OpenApiContractTest`, `PermissionCatalogTest`, `WorkflowEventContractTest`).

Still to prove on a deployment, not by a unit test: the four-sample compose run through `ops/smoke/workflow.sh`, the 50
chaos cycles in `workflow-chaos.sh`, and the 1,000-instance baseline. Named limitations: payload and step-output byte
caps are not enforced, and a wait matches an event type rather than a correlation expression.

## Success metrics

- Smoke script: 4 of 4 sample workflows reach their expected end state with the expected calls recorded.
- Crash test: 0 lost steps, 0 steps with two completion records, 100% of repeated sends carry the same key, across 50 kill
  and restart cycles at random points.
- RLS and API probes: 0 rows or fields leak across tenants on every table and endpoint.
- Revocation: 100% of calls after revocation fail `credential_invalid` within 10 minutes; 100% fail at once after disabling the
  Tenant System.
- SSRF suite: every refused destination refused; 0 successful calls to private ranges.
- Throughput guard (not a promise): a baseline run of 1,000 instances with 10 steps each completes without error and without
  a step running later than its due time plus the sweep interval; record the numbers in `docs/workflow.md`.
- All tests, ArchUnit and Modulith gates green; `contracts` tests prove openapi, permissions and events match the running app.
