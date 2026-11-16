---
phase: 8
title: "Durable time: delay and wait-for-event"
status: pending
priority: P1
effort: "L"
dependencies: [6]
---

# Phase 8: Durable Time

## Goal

A workflow can pause for a duration, until a date, or until an event arrives for its correlation key, with a timeout, across
restarts, and the event-versus-timeout race has exactly one winner.

## Context

Timers are rows swept with `SKIP LOCKED`, never in-memory schedulers. The early-event buffer is the `inbound_event` table from
phase 7. A wait registers a subscription keyed by `(tenant, source, event type, correlation key)`.

## Files to create

- Migration `V5__timers.sql`: `timer` (token, kind `DELAY|WAIT_TIMEOUT|TASK_DUE|RETRY`, `due_at`, fired), `wait_subscription`
  (token, source, event type, correlation key, expires at), indexes on `due_at` and on the correlation tuple, RLS
- `workflow/contexts/runtime`: `DelayExecutor`, `WaitExecutor`, `TimerSweep`, `EventMatcher`
- Tests: race and clock tests

## Tasks

1. `delay` node: `for` (ISO 8601 duration, expression allowed) or `until` (instant); creates a token WAITING and a `DELAY`
   timer in one transaction.
2. `wait` node: `event` (source, type), `correlation` (expression), `timeout` (duration), outputs `event` or `timeout` as the
   branch taken. Registration first checks the buffered `inbound_event` rows for a match received at or after the instance
   started; a match proceeds at once.
3. `EventMatcher` runs in the hook transaction (phase 7): for each new event it finds waiting subscriptions and resolves them.
4. Resolution is one conditional `UPDATE token SET status='READY', outcome=? WHERE id=? AND status='WAITING'`. Whichever of the
   event and the timer updates a row first wins; the loser updates zero rows and does nothing. The winner removes the other
   side (subscription or timer) in the same transaction.
5. `TimerSweep` fires due timers in batches with `SKIP LOCKED`, re-checks `fired` and token status, and is idempotent: running
   it twice, or two sweepers at once, changes nothing the second time.
6. Cron-like repetition is not a node (schedules belong to triggers, phase 7); a loop with delay uses `foreach` or a counted
   loop with the step cap.

## Validation

- Tests with a fake clock: delay resumes at the due time and not before; a worker restart during a delay resumes it; a timer
  swept twice runs the next step once.
- Race tests: event and timeout resolved concurrently 1,000 times, always one outcome, never both; an event before the wait
  registers is matched from the buffer; an event after the timeout is ignored.
- The nudge workflow's wait (smoke, phase 15) resolves on an event and on timeout.

## Risks

- Sweep cost with many timers: indexed on `due_at` where not fired; the batch size and interval are configuration, and phase 14
  records a baseline.
