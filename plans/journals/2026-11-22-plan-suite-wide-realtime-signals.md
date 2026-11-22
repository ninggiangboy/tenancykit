---
title: "Plan: suite-wide realtime signals"
date: 2026-11-22
summary: Advisory interview and 14-phase plan for an infra/realtime SSE service; broker-only signal rows are required because the in-process relay feeds every outbox row to every listener.
---

# Plan: suite-wide realtime signals

## What happened
An advisory interview settled the scope for pushing changes to console users in real time, and a 14-phase plan was written at `plans/261122-1519-suite-realtime-signals`.

Decisions taken in the interview:
- A new `backend/services/infra/realtime/` service, built like mailer. Every app sends a `realtime.push` command through its outbox to `realtime.commands`.
- Delivery over SSE to signed-in Platform and Tenant users only. Tenant customers are out of scope.
- Signals are small hints. REST stays the source of truth, so a lost signal costs latency, never correctness.
- No Redis. RabbitMQ carries the fan-out and Postgres holds a short replay window (5 minutes, at most 100 signals per user).
- Bursts are coalesced on the server (one `resync` above 20 signals per second) and on the client (300 ms batches, one refetch per visible query key, toasts collapsed to "+N").
- Scale target is 1,000 to 5,000 concurrent connections.

The user changed course twice during the interview: from "with Redis" to "no Redis", and from "customers included" to "console users only".

## Key finding
`OutboxRelay.claim` hands every `PENDING` outbox row to every Spring listener, and `WebhookEventListener.on` accepts any `EventEnvelope`. A `realtime.push` row written beside a webhook event would therefore reach the webhook machinery. The plan writes signal rows broker-only (`status = DELIVERED`, `publish_status = PENDING`), so only `BrokerRelay` publishes them. `ExportRoutes` maps one exchange per type, so the apps that already have a relay (billing, survey, notification) only need an extra export entry.

## Decision
Mapping from event to permission lives with the producer through a shared `EventPublisher` decorator in a new `core-realtime` module. Realtime holds no mapping table. A coverage gate and a registry of explicit no-signal events keep "mirror the event catalog" enforceable. Billing's `usage.rated` is excluded as a metering firehose.

## Next steps
- Phase 1: commit or merge the pending `refactor/group-services-by-kind` renames, then record a green baseline.
- Execute with `/ak-cook plans/261122-1519-suite-realtime-signals/plan.md`, using one PR per producer app so each can be reverted alone.
- Unverified: whether each producer app already has an AMQP publisher with confirms, and whether Mailroom and Attribution have events to mirror.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
