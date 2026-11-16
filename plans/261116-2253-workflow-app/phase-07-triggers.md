---
phase: 7
title: "Triggers: inbound webhook, manual start, schedule"
status: pending
priority: P1
effort: "L"
dependencies: [6]
---

# Phase 7: Triggers

## Goal

A Tenant starts workflows from a signed webhook of a suite app, a signed webhook from an external system, a manual API call
with an idempotency key, or a cron schedule, with every inbound event deduplicated and kept for waits.

## Context

Suite apps send signed webhooks to Tenant endpoints. `core-webhook` defines header sets for Billing and Survey only
(`core/core-webhook/.../WebhookHeaderSet.java`); CRM and loyalty copied Survey's webhook context. **Read before writing the
verifier:** `crm/contexts/webhook`, `loyalty/contexts/webhook`, and each `contracts/events/*/README.md` for the actual header
names, signature base string (`timestamp + "." + rawBody`, `v1=` prefix) and envelope (`id, type, tenantId, occurredAt, data`).
A Tenant registers the engine's hook URL and secret in each source app's own webhook-endpoint API; the engine never
registers itself. Tenant comes from the host of the hook URL (the Tenant's hostname).

## Files to create

- Migration `V4__triggers.sql`: `trigger` (kind `APP|EXTERNAL|MANUAL|SCHEDULE`, source, event type filter, signing secret
  encrypted, definition code, enabled), `inbound_event` (tenant, source, event id unique per source, type, payload, received at,
  expires at), `schedule` (cron, timezone, next due, last fired), RLS
- `workflow/contexts/triggers`: domain, application (`ReceiveEvent`, `FireManual`, `FireSchedule`), adapter (public hook
  controller, jOOQ)
- OpenAPI: trigger management (`workflow:trigger:manage`), `POST /api/v1/instances` (manual, `Idempotency-Key`),
  `POST /hooks/{triggerId}` (public, signed)
- Tests: verifier per source header set, replay, dedup, schedule skew

## Tasks

1. Hook endpoint verifies the signature with the trigger's secret in constant time, rejects a timestamp outside the window (5
   minutes), stores the event (dedup by source event id, 200 for a duplicate), then starts an instance for each enabled trigger
   whose filter matches, in the same transaction. Unknown trigger or bad signature answers one generic status.
2. Per-source verifier table: `billing` (`Billing-*`), `survey`, `crm`, `loyalty`, `mailroom` read from the header sets found in
   the read-before-write step, and a generic `external` HMAC with a documented header set of the engine's own.
3. A filter is an expression over the event (`type`, `data`), evaluated with the phase 4 evaluator; the correlation key is
   another expression evaluated at start and stored on the instance.
4. Manual start: `POST /api/v1/instances` with `definition`, `input`, optional `correlationKey`; the same `Idempotency-Key`
   with the same body returns the first instance, with a different body 409.
5. Schedule: a worker job fires due schedules inside a transaction that advances `next_due` first, so two workers never fire
   one tick; missed ticks while down fire once, not N times (documented catch-up rule).
6. `inbound_event` rows are retained for the early-event window (default 7 days, up to 30 per Tenant) and purged by the
   housekeeping job.

## Validation

- Tests: each source's real sample delivery verifies; tampered body, stale timestamp, wrong secret all refused alike;
  duplicate event id starts one instance; two workers and one schedule tick start one instance.
- A smoke call with a real Survey or Billing delivery through Caddy (phase 15 wires the route) starts an instance.

## Risks

- Header-set guesswork: avoided by reading the sources first; a mismatch is a test failure with real fixtures.
