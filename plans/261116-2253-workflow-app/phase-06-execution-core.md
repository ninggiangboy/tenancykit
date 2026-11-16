---
phase: 6
title: "Execution core"
status: pending
priority: P1
effort: "L"
dependencies: [5]
---

# Phase 6: Execution Core

## Goal

A published workflow can be started and its tokens walked to the end by competing workers, durably and idempotently, using
only trivial nodes (`end`, `fail`, a no-op `set`), with a test harness that proves the core is safe under crash and
duplication before any real node exists.

## Context

This is the hardest phase and the plan's first milestone. Postgres is the only source of truth. RabbitMQ only wakes workers.
Reuse the lease and sweep pattern Billing already uses (find it with `git grep -n "SKIP LOCKED" -- billing core`) and the
job lease tables from the skeleton. Tenant context: workers process rows of every tenant, so the sweep opens a tenant scope
per claimed row (see the `TenantSweep` job from the skeleton).

## Files to create

- Migration `V3__runtime.sql`: `instance` (status `RUNNING|WAITING|COMPLETED|FAILED|CANCELLED|COMPENSATING`, input,
  correlation key, version id, started by, optimistic `version`), `token` (instance, node id, status
  `READY|RUNNING|WAITING|DONE|FAILED`, parent token, branch key, iteration, attempt, `available_at`, `lease_owner`,
  `lease_until`), `step_run` (append-only: token, node, attempt, started, finished, outcome, redacted request and response,
  idempotency key), indexes for `status, available_at` and for correlation lookups; RLS on all
- `workflow/contexts/runtime`: domain (`Instance`, `Token`, `NodeExecutor` interface, `StepOutcome`), application (`StartInstance`,
  `ClaimAndRun`, `Sweeper`), adapter (jOOQ, `workflow.runnable` publisher and consumer)
- `apps/app-boot`: `RunnableConfiguration`, `jobs/RunnableSweepJob`
- Tests under `workflow/apps/app-boot/src/test/java/.../runtime/`

## Tasks

1. `StartInstance` (manual path only here) pins the current published version, creates the instance and its first token, and
   commits; the trigger and idempotency by key arrive in phase 7.
2. `ClaimAndRun`: select READY tokens due now with `FOR UPDATE SKIP LOCKED`, set RUNNING with a lease, commit; run the node
   outside the transaction when it must call out; then one transaction records the `step_run`, updates the token and creates
   the next tokens. Every state change is a conditional `UPDATE ... WHERE status = ? AND lease_owner = ?`; zero rows updated
   means someone else owns it and the worker abandons quietly.
3. `Sweeper` re-queues tokens whose lease expired and re-publishes READY tokens to `workflow.runnable`; a lost message costs
   only latency. The queue is declared by this app only, with the suite DLX pair.
4. The step's idempotency key is `sha256(instanceId : tokenId : nodeId : iteration)`, stable across retries and restarts; it is
   written to `step_run` before any outward call (used from phase 9).
5. Completion: when no token is live the instance becomes COMPLETED (or FAILED if a `fail` node was reached).
6. Harness: a fake clock, a fake node executor that can crash at chosen points (after claim, after call, before commit), and a
   multi-worker runner.

## Validation

- Tests: two workers claim one token (one wins); a worker killed after the call and before the commit leaves one completion
  after restart and the same key; a lease that expires is taken over; 1,000 instances run by 8 workers finish with no token
  stuck and no duplicate `step_run` completion; RLS holds.
- `workflow.runnable` message dropped on purpose: the sweeper still finishes the instance.
- Kill-and-restart test of the real worker process on the compose stack with a trivial workflow.

## Risks

- A lease shorter than a slow call causes a double run: the lease is extended while a node runs (heartbeat), and the duplicate
  is still harmless because the second worker's conditional update fails.
