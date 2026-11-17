---
phase: 12
title: "Failure handling, compensation and replay"
status: done
priority: P1
effort: "L"
dependencies: [10]
---

# Phase 12: Failure Handling, Compensation and Replay

## Goal

A failing step retries by policy, then routes to an error branch or to compensation; a stuck instance lands in a dead-letter
state a person can inspect, retry from the failed step, replay, or cancel.

## Context

The idempotency key is stable per logical step (phase 6), so a retry or a replay of a step re-sends the same key and a
key-honouring target produces one effect. `RetrySchedule` in `core-webhook` gives the backoff shape.

## Files to create or modify

- Migration `V9__failure.sql`: `step_run.error_class`, `instance.failure_reason`, `compensation_run`, dead-letter fields
- `workflow/contexts/runtime`: `RetryPolicy`, `ErrorRouter`, `CompensationRunner`, `ReplayService`
- OpenAPI: `POST /api/v1/instances/{id}/retry`, `.../replay`, `.../cancel`, `GET .../steps`
- Tests

## Tasks

1. Error classes: `retryable` (timeouts, 5xx, 429, connection errors), `fatal` (4xx other than 408 and 429, validation of a
   response mapping, `credential_invalid`, `limit_exceeded`). A node's `retry` policy gives max attempts, backoff base, cap and
   jitter; retries are `RETRY` timers, never in-process sleeps.
2. After retries, an `on_error` edge takes the failure as step output (`error.class`, `status`, `message` redacted); without an
   `on_error` the instance goes to `FAILED_STUCK` (dead letter) and stays inspectable.
3. Compensation: a node may declare `compensate` (an `http` node). When the instance takes a `compensate` edge or a `fail`
   node with `compensate: true`, `CompensationRunner` runs the compensations of completed steps in reverse completion order
   with their own idempotency keys; a compensation retries by its own policy; if one is dead-lettered the instance becomes
   `COMPENSATION_FAILED` and records which steps remain.
4. Retry from the failed step re-queues the failed token only; replay starts a new instance from the original input and
   version, linked to the old one; cancel stops tokens, cancels timers and tasks, and runs compensation only if the caller asks.
5. Every operator action is audited with the caller and recorded in history.

## Outcome

Shipped: migration `V12__failure.sql` (the phase file's "V9" was taken by for-each, and each phase reserves its own
range), `ErrorClassifier` and `RetryPolicy` in the runtime domain, `CompensationRunner`, the retry/replay/cancel use
cases, the steps read view, and `InstanceOperationsController` on `/api/v1/instances/{id}/{retry,replay,cancel,steps}`
with the existing `workflow:instance:manage` and `workflow:instance:read` codes. A retry is an `available_at` backoff on
the same token, so every attempt of one step sends the same `Idempotency-Key`; a step with no `on_error` dead-letters
its instance in `FAILED_STUCK`; a `fail` node with `compensate: true` rolls the completed steps back on a synthetic token
per compensation.

Deliberate simplifications, recorded here rather than silently dropped:

- A compensation retries after its own policy's backoff, waited in-process, because a rollback is synchronous and has no
  token to park; a step's retry is the durable `available_at` move the task asks for.
- The `on_error` edge records the failure envelope `{"error":{"class","message","status"}}` as the failed step's output,
  so the raw response body of a failed call is not kept in the timeline.
- A sub-workflow's child never dead-letters: it finishes itself `FAILED` and resumes the parent, because nothing else
  could ever resume the token that waits for the child. Only a top-level instance is dead-lettered.
- Retry is accepted from `FAILED_STUCK` only; `COMPENSATION_FAILED` is inspected and cancelled, not retried. One
  `workflow_compensation_run` row per compensated step carries the attempt count, instead of one row per attempt.
- `cancel?compensate=true` runs the rollbacks and then cancels the instance regardless of their outcome; a failed
  rollback stays visible on its `workflow_compensation_run` row.
- These POSTs carry no `If-Match`, because no `GET /instances/{id}` exists yet to hand out an instance ETag; they answer
  a state that cannot be retried or replayed with `409`. `contracts/openapi/workflow.yaml` was regenerated and is committed with this phase.
- `StructureChecker.reachable` now follows `compensate` edges, so a compensation-only http node is publishable at all.

## Validation

- Tests: a retryable failure succeeds on the third attempt with the same key each time; fatal failure takes `on_error`;
  compensation runs in reverse order; a failing compensation lands in `COMPENSATION_FAILED`; retry-from-failure after the
  target recovers completes the instance with one completion record per step.
- The erasure fan-out sample (phase 15) has one external branch that fails, retries, dead-letters, and is retried by hand.

## Risks

- Compensation can itself be unsafe to repeat: compensation nodes must use idempotent targets; publish warns when a
  compensation's target has no `Idempotency-Key` support in its OpenAPI.
