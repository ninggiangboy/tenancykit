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
| 8 Durable time | done | `119fb46a` |
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

## Phase 8 as landed

- `V5__timers.sql`: `workflow_timer` (DELAY/WAIT_TIMEOUT/TASK_DUE/RETRY) and `workflow_wait_subscription`,
  both tenant-scoped; `workflow_token.resume_branch`/`resume_payload` carry the branch a wait resumed on.
- `StepOutcome.parked(...)` with a `Reservation`; `ClaimAndRun.park` writes the timer/subscription in the
  same transaction as the step, moves the token to WAITING, and moves the instance to WAITING when no
  token is READY or RUNNING.
- `TokenRepository.park/resume` are conditional WAITING/RUNNING updates; the timer sweep and the event
  resolver race on that one update and the winner cancels the other side.
- `TimerSweep` + `TimerSweepJob` fire due timers per tenant; `WaitResolver` (runtime api) is called by the
  signed hook inside its transaction; `TriggeredEventLookup` (boot) fills the early-event port from the
  triggers buffer without a context cycle.
- Executor: `delay` (`duration` ISO text or number, or `until`) and `wait` (`source`, `event`, `timeout`,
  `on_timeout`, `next`); a buffered event satisfies a wait immediately.
- Tests: delay parks/resumes; timeout wins and a late event is ignored; event wins and cancels the timer;
  buffered event satisfies a wait; 25 concurrent event-vs-timer races resume exactly one branch.

Caveat found while landing it: the old `UPDATE ... WHERE id IN (SELECT ... SKIP LOCKED LIMIT 1) RETURNING`
claim could return more than one row under eight workers. `claimNext` now selects the id first (locking it)
and updates that one row, guarded by `status = READY`.

## Next: phase 9 (HTTP actions, connections, vault) concrete plan

Read `phase-09-http-actions-connections-and-vault.md` for the full scope; these are the seams it lands in.

1. `V6__connections.sql`: `workflow_connection` (tenant, name, kind BEARER/BASIC/API_KEY/HMAC/NONE,
   allowed host pattern, encrypted secret, key id, last verified) and `workflow_platform_credential` (one
   per tenant: client id, encrypted secret, key id, status), both with RLS. Reuse
   `workflow.shared.secret.SecretBox`; it already produces `v1.<keyId>.<base64(nonce||ciphertext)>`.
2. New context `connections` with its own `build.gradle.kts` (copy the triggers module's wiring):
   domain + application (`RegisterCredential`, `ManageConnection`, `TokenProvider`, `HttpCall` ports) +
   adapter (controllers under `/api/v1/connections` and `/api/v1/platform-credential`, jOOQ stores, the
   Platform token client and the outbound sender).
3. `TokenProvider` is a port in the connections application layer with an adapter cache keyed by
   `(tenant, audience)`, refreshed about a minute before `exp`, one in-flight request per key, and
   `Retry-After` on 429. A 401 from the token endpoint flips the credential to `INVALID`.
4. Only `connections` may make an outbound call. Runtime's `HttpNodeExecutor` (domain) must go through a
   runtime-owned port, implemented in boot by delegating to the connections api — the same trick as
   `TriggeredEventLookup`, so neither context imports the other's internals.
5. Reuse `core/core-webhook` `DestinationPolicy`, `GuardedDnsResolver`, `WebhookHttpSender` and
   `RetrySchedule`; do not write new guards. App calls resolve the base URL from configuration and the
   tenant host from the replica; an external call is limited to the connection's allowed host pattern.
6. History keeps a redacted request/response (authorization, cookies, the connection's secret, configured
   header names). `StepRun.response` is where the redaction shows up.
7. Tests: key rotation still decrypts; no secret in any history row; one token reused until near expiry
   and refreshed once under 50 concurrent calls; 401 flips the credential; the SSRF suite refuses
   localhost, 127.0.0.1, 169.254.169.254, `[::1]`, a name resolving to 10.0.0.1, redirects to each, and an
   allowed-host mismatch, with no connection made.

## Cautions

- Another session has unstaged work in `crm/`, `loyalty/`, `platform/`, `ops/`, `docs/` and
  `contracts/openapi/attribution.yaml`. Never `git add -A`; stage explicit workflow paths only.
  The phase-7 commit had to be rewritten once because the index already held those files.
- Every read that needs RLS must run inside `UnitOfWork.transactional`; a bare `dsl` call outside a
  transaction sees nothing.
- Regenerating jOOQ needs a green compile; stub new-column usages first, run
  `-Djooq.update=true`, then restore.
