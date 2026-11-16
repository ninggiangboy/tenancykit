---
phase: 12
title: "Failure handling, compensation and replay"
status: pending
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

## Validation

- Tests: a retryable failure succeeds on the third attempt with the same key each time; fatal failure takes `on_error`;
  compensation runs in reverse order; a failing compensation lands in `COMPENSATION_FAILED`; retry-from-failure after the
  target recovers completes the instance with one completion record per step.
- The erasure fan-out sample (phase 15) has one external branch that fails, retries, dead-letters, and is retried by hand.

## Risks

- Compensation can itself be unsafe to repeat: compensation nodes must use idempotent targets; publish warns when a
  compensation's target has no `Idempotency-Key` support in its OpenAPI.
