---
phase: 14
title: "History, quotas, retention and runtimes"
status: pending
priority: P1
effort: "M"
dependencies: [13]
---

# Phase 14: History, Quotas, Retention and Runtimes

## Goal

A Tenant can read exactly what ran and why; no Tenant can exhaust the engine; old data is purged; and the api, worker and
migrate runtimes split cleanly.

## Context

Hard caps exist so that one Tenant's loop or fan-out cannot starve others. Defaults below are configuration, not code
constants, and a per-Tenant override is a row in `tenant_quota` set by an operator (no Tenant-facing API, no billing).
The runtime split follows `plans/261111-1620-api-worker-runtime-split`.

## Files to create or modify

- Migration `V10__quotas.sql`: `tenant_quota`, counters or derived queries for concurrent instances
- `workflow/contexts/runtime`: `QuotaGuard`, `HistoryQueries`, `RetentionJob`
- OpenAPI: `GET /api/v1/instances` (core-query contract), `GET /api/v1/instances/{id}` (status, current tokens, pinned
  version), `GET /api/v1/instances/{id}/steps` (the timeline: node, attempt, times, outcome, redacted request and response,
  idempotency key, branch decision)
- `apps/app-boot`: runtime profiles, `jobs/*` wiring; tests

## Tasks

1. Quotas (defaults): concurrent running instances 1,000 per Tenant, steps per instance 1,000, for-each items 1,000, parallel
   branches 50, sub-workflow depth 5, trigger and manual payload 256 KB, step output 256 KB, active definitions 200. Starting
   past the instance cap answers `429` with a reason; the others fail the instance with `limit_exceeded:<name>`.
2. History endpoints per the OpenAPI above; filters by status, definition, correlation key and time through the shared
   list-query contract (`core-query`), as billing's lists do.
3. Retention: completed and failed instances, their steps and inbound events purge after 90 days (configurable), in bounded
   batches by a job that leases like the others; open instances never purge.
4. Runtimes: `api` serves HTTP and hooks only; `worker` runs the claim loop, sweepers, timers, schedules, outbox and
   webhook delivery; `migrate` applies Flyway and exits. A health indicator reports sweep lag.
5. Record a baseline in `docs/workflow.md`: 1,000 instances of 10 steps with 8 workers (steps per second, lag of due timers).

## Validation

- Tests: quota rejections and failures with stable reasons; retention purges only eligible rows and leaves open instances; the
  history of a finished instance reproduces its path without any secret.
- Each runtime starts alone on the compose stack and `api` runs no job.

## Risks

- Quotas hide a bug as a failure: every quota failure is a first-class reason and an event (`instance.failed`).
