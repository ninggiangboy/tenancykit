# Workflow app — phases 1–7 landed, phases 8–15 remaining

Date: 2026-11-17. Plan: `plans/261116-2253-workflow-app/`.

## State

| Phase | Status | Commit |
|---|---|---|
| 1 Scope, contracts, docs | done (pre-existing) | earlier |
| 2 Platform purpose + refund grant | done (pre-existing) | `3419bd7c` |
| 3 Build skeleton, replica, gates | done, build green | `58b93b52` |
| 4 Expression language and vectors | done | `3383a46e` |
| 5 Definition authoring and publish | done | `964efa66` |
| 6 Execution core | done | `b469fd11` |
| 7 Triggers (hook, manual, schedule) | done | `b03cc634` |
| 8 Durable time | not started | |
| 9 HTTP actions, connections, vault | not started | |
| 10 Control flow | not started | |
| 11 Human tasks | not started | |
| 12 Failure, compensation, replay | not started | |
| 13 Workflow webhooks and events | not started | |
| 14 History, quotas, retention, runtimes | not started | |
| 15 Samples, chaos, isolation, ops, docs | not started | |

`./gradlew build` in `workflow/` is green after phase 7 (ArchUnit, Modulith, RLS, jOOQ drift,
OpenAPI contract, permission catalog and all integration tests).

## What exists, and the conventions to extend

### Expression language (phase 4)
- `workflow/contexts/definitions/src/domain/.../expression/`: `Expr`, `ExprParser`, `ExprChecker`,
  `Evaluator`, `Value`, `Operator`, `Limits`, `Json`, `Environment`, `MapEnvironment`.
- References: `{"input": path}`, `{"step": nodeId, "path": path}`, `{"var": name}`,
  `{"item": path}`, `{"index": true}`. No clock in expressions.
- Operators: Survey's list plus `concat` and `coalesce`.
- Contracts: `contracts/workflow/expression.schema.json`, `expression-vectors/*.json`; the vector
  test lives in `workflow/apps/app-boot/src/test/java/dev/ngb/workflow/definitions/domain/expression/`.

### Graph model and publishing (phase 5)
- Graph JSON: `{"start": "<triggerId>", "nodes": [{"id", "type", ...settings}]}`.
- Node types and settings keys:
  - `trigger` `{next}`; `end` `{}`; `fail` `{reason?: expr}`
  - `set` `{values: {name: expr}, next}`
  - `http` `{app, method, path, next, on_error?, compensate?, body?, headers?}`
  - `branch` `{cases: [{when: expr, next}], else?}`
  - `delay` `{duration: ISO-8601, next}`
  - `wait` `{event, match?: expr, timeout?, on_timeout?, next}` (source+type still to define in phase 8)
  - `task` `{title, assignee: {user|group}, due?, next}`
  - `parallel` `{branches: [nodeId...], join: nodeId}`; `join` `{next}`
  - `foreach` `{items: expr, each: nodeId, next}` (iteration entry is `each`, not `body`)
  - `subflow` `{definition: code, input?: expr, next, on_error?, compensate?}`
- `StructureChecker` enforces: 200 nodes / 400 edges, one trigger which is the start, edges exist,
  reachability, parallel join is a JOIN, join belongs to a parallel, compensation is an HTTP node,
  a cycle repeats only through a for-each, at least one END or FAIL, expressions resolve, 2,000
  expression nodes per version.
- Endpoints under `/api/v1/definitions`; ETag is the definition's `version`; draft PUT and publish
  and retire require `If-Match` (428 without). Publish of an invalid draft is 422 with
  `problems: [{pointer, code, message}]`.
- Tables: `workflow_definition`, `workflow_version` (immutable, `revoke update`).

### Execution core (phase 6)
- Tables: `workflow_instance` (variables, optimistic `version`, idempotency key/hash),
  `workflow_token` (READY/RUNNING/WAITING/DONE/FAILED, lease owner/until, attempt),
  `workflow_step_run` (append-only, unique `(token_id, attempt)`, `revoke update`).
- `ClaimAndRun.runOnce()`: claim one due token with `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE
  SKIP LOCKED) RETURNING`, commit; load instance and pinned version **inside `UnitOfWork.transactional`**
  (RLS needs the tenant set); run the node; then one transaction records the step, variables and next
  tokens. `tokens.finish` is conditional on status + owner.
- Manual start: `POST /api/v1/instances` (`instance:manage`) resolves the newest published version,
  honours `Idempotency-Key` (same body → same instance, different body → 409).
- `workflow.runnable` queue (wake-up only) + `RunnableSweepJob` per tenant; `workflow.runnable` is
  declared in both runtimes and carries a tenant id.
- `DefaultNodeExecutor` handles `trigger`, `set`, `end`, `fail`; everything else fails with
  `node_not_supported:<type>` until its phase lands.

### Triggers (phase 7)
- Tables: `workflow_trigger` (kind APP/EXTERNAL/MANUAL/SCHEDULE; signed kinds carry an encrypted
  secret), `workflow_inbound_event` (unique `(tenant, source, event_id)`, 7-day buffer),
  `workflow_schedule` (cron, timezone, `next_due`, `last_fired`).
- `POST /hooks/{triggerId}` is public: verify HMAC-SHA256 of `timestamp + "." + rawBody`, `v1=` hex,
  ±5 minutes, per-source header sets (`Billing-*`, `Survey-*`, `Crm-*`, `Loyalty-*`, `Mailroom-*`,
  `X-Workflow-*` for external); duplicate event id → 200 with `duplicate: true`; bad signature,
  stale timestamp, unknown trigger and tampered body all → 401.
- Cron uses Spring's six-field format (`0 0 2 * * *`), not five fields.
- `SecretBox` (`libs/shared-core`) is AES-256-GCM with `v1.<keyId>.<base64(nonce||ciphertext)>`;
  the key comes from `workflow.secrets.master-key` (base64 32 bytes) and the app refuses to start
  without it. Tests set it in their `@DynamicPropertySource`.

## Deliberate approximations to revisit

- `wait` node settings still need a source/type shape (phase 8 decides it; keep `wait.event` as the
  type and add `source`/`correlation` in V5 or reuse `wait.match`).
- `foreach` uses `each` for the iteration entry because `body` is already the HTTP request body.
- The trigger `filter`/`correlation`/`input` expressions read the whole event envelope as `input`.
- Schedules are `SCHEDULE` triggers with a row in `workflow_schedule`; a missed tick fires once
  because `next_due` is computed after `now`.
- App-boot wiring is manual `@Configuration` classes; every new context needs one.

## Next: phase 8 (durable time) concrete plan

1. `V5__timers.sql`: `workflow_timer` (kind DELAY/WAIT_TIMEOUT/TASK_DUE/RETRY, `due_at`, `fired_at`)
   and `workflow_wait_subscription` (source, event type, correlation key, `expires_at`,
   `resolved_at`), plus `workflow_token.resume_branch text` and `resume_payload jsonb` (or a
   dedicated resume table) to hand the resumed wait its event.
2. Extend `StepOutcome` with a parked variant: timer request and/or wait subscription; `ClaimAndRun`
   records the step, moves the token to WAITING (conditional), inserts the timer/subscription, and
   sets the instance WAITING when no token is READY/RUNNING.
3. `delay`: park with a `DELAY` timer at `now + duration`; on fire, token becomes READY with
   `resume_branch=delay`; the executor then takes `next`.
4. `wait`: park with a subscription and a `WAIT_TIMEOUT` timer; the event matcher resolves
   WAITING→READY (`resume_branch=event`, payload = event data) and removes the timer; the timer
   resolves WAITING→READY (`resume_branch=timeout`) and marks the subscription resolved. Resolution
   is one conditional UPDATE; whichever side updates the row wins and the loser does nothing.
5. `EventMatcher` runs inside the phase-7 hook transaction after the event is stored, and also checks
   the buffer on registration so an event that arrived earlier is matched.
6. `TimerSweep` per tenant, `SKIP LOCKED`, batched; a second sweep changes nothing.
7. Tests with a fake clock: delay resumes not before its due time; restart during a delay resumes;
   event-vs-timeout race resolved 1,000 times yields one branch; a duplicated event is ignored;
   a timer swept twice runs the next step once.

## Cautions

- Another session has unstaged work in `crm/`, `loyalty/`, `platform/`, `ops/`, `docs/` and
  `contracts/openapi/attribution.yaml`. Never `git add -A`; stage explicit workflow paths only.
  The phase-7 commit had to be rewritten once because the index already held those files.
- Every read that needs RLS must run inside `UnitOfWork.transactional`; a bare `dsl` call outside a
  transaction sees nothing.
- Regenerating jOOQ needs a green compile; stub new-column usages first, run
  `-Djooq.update=true`, then restore.
