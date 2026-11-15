---
phase: 9
title: "Events and webhooks"
status: pending
priority: P1
effort: "M"
dependencies: [2, 7]
---

# Phase 9: Events and Webhooks

## Goal

Survey facts reach suite apps on `survey.events` and Tenant Systems as signed `Survey-*` webhooks, both from the same
outbox, with delivery mechanics from `core-webhook`.

## Files to create / modify

- Create: `survey/contexts/webhook/...` (endpoints, deliveries, event-type catalog, claim and deliver use cases)
- Create: migration `V6__webhooks.sql`: `webhook_endpoints` and `webhook_deliveries`, shaped like Billing's tables
  (copy and adapt; no shared schema)
- Modify: `survey/contexts/authoring` and `collection` to append outbox rows for `survey-version.published`,
  `survey.closed` and `survey-response.submitted`
- Modify: `survey/build-logic` or module builds to depend on `dev.ngb:core-webhook`
- Modify: `contracts/openapi/survey.yaml`

## API

| Method and path | Permission |
|---|---|
| `GET/POST /webhook-endpoints`, `GET/PUT/DELETE /webhook-endpoints/{id}`, `POST /webhook-endpoints/{id}/rotate-secret` | `survey:webhook:manage` |
| `GET /webhook-deliveries?endpoint&status`, `POST /webhook-deliveries/{id}/retry` | `survey:webhook:manage` |

## Tasks

1. **Outbox.** The three events are appended in the same transaction as the fact (publish, close, submit), with the
   envelope and schemas from phase 1. The relay publishes to `survey.events` (worker only).
2. **Webhooks.** A worker listener records one delivery per matching endpoint from the outbox stream, then claims and
   delivers with `WebhookSigner`, `WebhookHttpSender`, `DestinationPolicy` and `RetrySchedule` from `core-webhook`
   using `WebhookHeaderSet.SURVEY`. Follow Billing's `RecordDeliveriesUseCase` and `DeliverWebhooksUseCase` shape.
3. **Secrets.** Endpoint signing secrets are generated server-side and shown once, on create and on
   `rotate-secret`, as Billing's `EndpointController.rotateSecret` does (no overlap window; Billing has none either).

## Verification

- `cd survey && ./gradlew test --tests '*webhook*' --tests '*Outbox*'` green.
- Tests: a submit produces one `survey-response.submitted` on the broker (Testcontainers RabbitMQ) validating against its
  schema; a webhook to a local test server carries a `Survey-Signature` that a verifier with the endpoint secret
  accepts; a URL resolving to `127.0.0.1` or `169.254.169.254` is blocked; a `500` reply is retried on the schedule.

## Risks

- Copying Billing's tables carries Billing-only columns; review each column against survey's event catalog.
