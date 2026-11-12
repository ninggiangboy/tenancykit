---
phase: 11
title: "In-app notifications"
status: cancelled
priority: P2
effort: "2d"
dependencies: [10]
---

# Phase 11: In-app realtime notifications

> **Cancelled on 2026-11-12.** The Notification Hub is dropped from the suite, so this phase is not built. Webhooks stay in Billing with their `Billing-*` headers. The text below is kept only as a record of what was planned.

## Goal

A `notification.requested` with channel `in_app` stores one notification per user recipient and pushes it immediately to that user's open connections over Server-Sent Events. Users list, read and mark notifications through the Hub API.

## Key insights

- A browser `EventSource` cannot send an `Authorization` header. The console will read the stream with `fetch` and a streaming body, sending the Keycloak bearer token. The endpoint is therefore a plain authenticated `text/event-stream` response; no cookie or token-in-URL is needed.
- One Hub instance in this plan. The emitter registry is in memory. More instances need a fan-out (Postgres `LISTEN/NOTIFY` or a per-instance RabbitMQ queue); that is recorded in docs as the scaling step, not built.
- Reconnects lose nothing: each SSE event id is the notification id (UUID v7, time-ordered), and `Last-Event-ID` returns the newer rows first.

## Files

Create:
- `notification/contexts/inapp/` (package root `dev.ngb.notification.inapp`): domain `Notification` (tenant, user, template key, title, body, link, created_at, read_at); application `RecordInAppUseCase` (called by the request acceptance from phase 10 for `user` recipients), `MarkReadUseCase`, queries; adapter persistence, `stream/SseNotificationStream` (registry keyed by `(tenant_id, user_id)`, heartbeat every 25 seconds, emitter timeout 30 minutes), web `NotificationController`.
- Templates `templates/billing.invoice-created/{en,vi}.inapp.mustache` (title and body sections).
- `notification/apps/app-boot/src/main/resources/db/migration/V3__inapp.sql`: `notifications` with RLS and an index on `(tenant_id, user_id, id desc)`.

Modify:
- `notification/contexts/email/.../AcceptNotificationRequestUseCase.java`: route `user` recipients with channel `in_app` to `RecordInAppUseCase` in the same unit of work; the push to open streams happens after commit.

## API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/notifications?unread=true&before=<id>&limit=50` | the caller's own notifications only |
| POST | `/api/v1/notifications/{id}/read` | 404 for another user's id |
| POST | `/api/v1/notifications/read-all` | |
| GET | `/api/v1/notifications/stream` | `text/event-stream`; honours `Last-Event-ID` |

No extra permission: the caller must be an authenticated user of a tenant that enabled `notification`. Tenant systems get 403 on these endpoints.

## Implementation steps

1. Domain, migration, persistence, queries.
2. Request routing from the acceptance use case; push after commit (`TransactionSynchronization.afterCommit`).
3. SSE stream with heartbeat, timeout, cleanup on disconnect, and `Last-Event-ID` catch-up.
4. Tests: a request with two user recipients creates two rows; an open stream for user A receives A's event within 1 second and never B's; reconnect with `Last-Event-ID` returns only newer rows; marking another user's notification answers 404.

## Todo

- [ ] Notification domain, table, queries
- [ ] Routing from `notification.requested`, push after commit
- [ ] SSE endpoint with heartbeat and catch-up
- [ ] List and read APIs
- [ ] Tests for isolation and catch-up

## Verification

- `cd notification && ./gradlew build` green.
- With compose: `curl -N -H "Authorization: Bearer $USER_TOKEN" http://acme.localhost/notification/api/v1/notifications/stream` prints an event after an invoice is created for tenant `acme`.

## Risks

- Too many open connections. Mitigation: at most 5 streams per user; the sixth closes the oldest.

## Security

- The stream filters by the authenticated user and tenant at registration; no client-supplied user id exists anywhere in this API.
