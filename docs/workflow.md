# Workflow

Workflow is the suite's app for letting a Tenant **describe a flow instead of writing one**. A Tenant publishes a
versioned JSON graph; a durable engine on PostgreSQL and RabbitMQ walks it, pauses it for time or an event, assigns a
human task, fans out and joins, calls suite apps and external systems, and records every step.

It is the Tenant side of the suite rule: workflow acts as the Tenant's own system, so it calls apps by their public
API with a Tenant System credential and receives their signed webhooks as triggers. It binds to no app's exchange,
and no app learns that workflow exists. The rule is written once in
[contracts/messaging/README.md](../contracts/messaging/README.md#the-suite-rule).

## Decisions

| Area | Decision |
|---|---|
| Who drives it | A Tenant's own system, inside the suite's rules. Workflow holds a dedicated Tenant System credential, created and revocable in Platform |
| Definition format | JSON graph, versioned and immutable once published. A draft is edited with `If-Match`; an instance is pinned to the version it started with |
| Node types | `trigger`, `http`, `branch`, `delay`, `wait`, `task`, `parallel` and `join`, `foreach`, `subflow`, `set`, `end`, `fail` |
| Edges | `next`, `on_error`, and a per-node `compensate` action |
| Expressions | A closed, side-effect-free language copied from Survey's semantics, never imported from it. No clock, no network and no host access; time comes from nodes |
| Core | Written in this app on PostgreSQL (the source of truth) and RabbitMQ (a wake-up only: losing a message costs latency, never correctness). No external engine |
| Delivery | At least once, with one stable `Idempotency-Key` per logical step and exactly one completion record per step. Not exactly once |
| Failure | Retry by policy, an `on_error` edge, a dead-letter state, replay from the failed step, and saga compensation in reverse completion order |
| People | A human task is assigned to a user or a role, has a due time and an escalation, and ends with a decision |
| Isolation | A Tenant reads and changes only its own definitions, instances, tasks, connections and history, on every table and endpoint, through row-level security and the tenant taken from the host and token |

## The Tenant System credential

Workflow does not have its own identity model. The Tenant creates a Tenant System with `purpose=WORKFLOW` in Platform,
grants it the app codes its flows need (for example `customer:customer:read` and `mailroom:email:send`), and registers the
resulting credential in workflow through the API. The credential's secret is encrypted at rest with a key outside the
database, is write-only, and is never written to history, events or logs. Workflow mints one short-lived suite token
per audience from Platform, caches it until shortly before expiry, and treats a `401` as `credential_invalid`; revoking
the credential in Platform therefore stops every later call within one token lifetime, and disabling the Tenant System
stops it at once.

`billing:refund:create` is grantable to a Tenant System, so a workflow can end in a real refund. It is an opt-in the
Tenant's administrator grants to the dedicated workflow credential; Billing's own limits and audit still apply.

## External calls

A call to a suite app names the app, method and path, and is checked against that app's published OpenAPI when the
definition is published. At run time the engine adds the Tenant's hostname (apps resolve the tenant from the host
alone), a token for that app's audience, and the step's stable `Idempotency-Key`.

A call outside the suite goes through a Tenant-owned connection: a name, a kind (`BEARER`, `BASIC`, `API_KEY`, `HMAC` or
`NONE`) and an allowed host pattern. `core-webhook`'s destination policy and guarded DNS resolver reject loopback,
link-local, private and metadata addresses and a redirect to one, and the call has a timeout and a response-size cap.

## Guarantees

- **Postgres is the only truth.** A worker claims a ready token with `FOR UPDATE SKIP LOCKED` and a lease, runs the
  node, and records the step and the next tokens in one transaction. Every state change is a conditional `UPDATE`, so
  two workers on one token cannot both win.
- **Time is durable.** Delays, waits, task dues and retry backoffs are rows with `due_at`, swept with `SKIP LOCKED`. A
  wait and its timeout race through one compare-and-set, so exactly one wins.
- **An early event is not lost.** Inbound events are kept for a window (7 days by default, up to 30 per Tenant), so an
  event that arrives before its wait registers still resolves it.
- **Calls are safe to repeat.** The same logical step always sends the same `Idempotency-Key`, so a retry, a lease
  takeover or a replay produces one effect in a target that honours the key.
- **History is redacted.** Authorization headers, cookies, connection secret values and configured header names never
  appear in a recorded request or response.

## Sample workflows

The four flows the app is proven against, as JSON fixtures under the app's test resources:

| Sample | Shape |
|---|---|
| Payment dunning | Trigger Billing `subscription.payment_failed`; email the customer; wait for a renewal or a payment with a 3-day timeout; pay the invoice; cancel the subscription if that fails |
| Refund approval | Manual or external trigger; a task for the `manager` role with a 24-hour due and an escalation; on approval a Billing refund, on rejection an email |
| Points nudge | Trigger loyalty `points.expiring`; email the member; wait for a Customer order correlated on the member's `externalRef` up to 14 days; on timeout a second email or a loyalty voucher call |
| Erasure fan-out | Trigger Customer `customer.erased`; a parallel fan-out over the Customer and loyalty erasure endpoints, mailroom's receiver removal and one external connection; join all; a completion event |

## What exists today

The scope, the permission catalog and these contracts are written. The app itself — build, replica, expression
evaluator, definitions, engine, triggers, timers, HTTP actions, control flow, tasks, failure handling, events,
history, quotas and the sample runs — is being built and is not yet in this document; each section above states the
decision, and this section will name what shipped as it ships.
