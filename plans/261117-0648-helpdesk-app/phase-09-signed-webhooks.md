---
phase: 9
title: "Signed webhooks"
status: pending
priority: P1
effort: "M"
dependencies: [5]
---

# Phase 9: Signed Webhooks

## Goal

Every actionable fact leaves helpdesk as a signed, retried webhook to the Tenant System's endpoints, so the Tenant can link
tickets to its customers, deliver generic-channel replies and run its own follow-ups.

## Context

Copy `booking/contexts/webhook` (committed): endpoint registry, per-endpoint signing key with a key id, the delivery table, the
retry schedule from `core-webhook` and the destination policy and DNS guard that refuses private addresses. Header names are
helpdesk's own `HelpdeskWebhookHeaders` (a `Helpdesk-*` set), as `BookingWebhookHeaders` is booking's; no `core` change.
Events come from the transactional outbox written by phases 5, 6, 8 and 14.

## Files to create

- Migration `V6__webhooks.sql`: `webhook_endpoints`, `webhook_deliveries`, key columns, RLS, jOOQ regenerated
- `helpdesk/contexts/webhook/src/{domain,application,api,adapter}/...`: `HelpdeskWebhookHeaders`, endpoint use cases and
  controller (`helpdesk:webhook:manage`), `DeliverWebhooksUseCase`, event-to-payload mappers for the eight types
- Tests: `WebhookSignatureTest`, `WebhookRetryTest`, `WebhookPayloadContractTest` (every payload validates against
  `contracts/events/helpdesk/*.schema.json`), `WebhookRowLevelSecurityTest`, `WebhookSsrfTest`

## Tasks

1. **Eight types** (a ninth, `requester.erased`, is emitted by phase 18). `ticket.created`, `ticket.replied`, `ticket.assigned`, `ticket.status-changed`, `ticket.merged`,
   `ticket.closed` are emitted from the ticket core now; `sla.at-risk` and `sla.breached` get their mappers and schemas here and
   their emission in phase 14. `ticket.closed` fires in addition to `ticket.status-changed` when the new status is `CLOSED`.
2. **`ticket.replied` payload.** `ticketId`, `ticketNo`, `messageId`, `direction`, `channel`, `visibility`, `author`
   (`kind` AGENT|REQUESTER, id), `bodyText`, `attachments` (ids and names, never bytes), `externalRef` of the requester when set,
   and for a generic channel the `conversationRef`, so the Tenant can deliver an outbound reply. Internal notes are **not**
   sent.
3. **Endpoints.** Each endpoint subscribes to a subset of types, has a URL checked by the destination policy at save and at
   send, and shows its signing secret once at creation and on rotation.
4. **Delivery.** At-least-once with the shared retry schedule and a dead state; each delivery carries an event id as the
   deduplication key and an attempt number; the worker sends outside any open database transaction.
5. **Replay.** `POST /webhook-deliveries/{id}/redeliver` for a dead or failed delivery.

## Verification

- A receiver verifying `timestamp.rawBody` with the endpoint secret accepts every delivery and rejects a tampered one.
- A destination resolving to a private address, `localhost` or a redirect to one is refused at save and at send.
- Killing the worker mid-delivery and restarting sends the event again with the same event id and `attempt + 1`.
- No internal note body appears in any payload (test over a ticket with a note).
- Every payload validates against its schema.

## Risks

- Header names become a public wire format: `contracts/events/helpdesk/README.md` lists them and a contract test pins them.
