---
phase: 11
title: "Signed webhooks"
status: pending
priority: P1
effort: "M"
dependencies: [8]
---

# Phase 11: Signed Webhooks

## Goal

Every fact booking publishes reaches the Tenant's registered endpoints as a signed `Booking-*` webhook, once per endpoint, with
retries, and a transaction that rolls back sends nothing.

## Context

Survey's `webhook` context wires `OutboxRelay` in process: a Spring event envelope becomes delivery rows in the relay's transaction.
Booking copies that context (as crm did) and declares no exchange, so no `BrokerRelay` and no export. Signing and sending come from
`core-webhook`; the six headers (`Booking-Event-Id`, `-Event-Type`, `-Timestamp`, `-Key-Id`, `-Signature`, `-Attempt`) are an app-local
`WebhookHeaderSet` built from the pattern phase 1 confirmed; core is not edited. Event types: `booking.held`, `booking.confirmed`,
`booking.rescheduled`, `booking.cancelled`, `booking.expired`, `booking.no-show`, `waitlist.offered`, `waitlist.lapsed`,
`series.created`, `series.cancelled`. Phases 8, 10 and 12 append them; this phase makes them leave.

## Files to create

- Migration `V10__webhooks.sql` (endpoints, signing keys, deliveries, attempts) copied from survey's and renamed; regenerate jOOQ
- `booking/contexts/webhook/src/{domain,application,api,adapter}/...` copied and renamed from `survey/contexts/webhook`
- `booking/apps/app-boot/.../config/WebhookConfiguration.java`, `OutboxConfiguration.java` (relay without broker)
- Tests: `WebhookDeliveryIntegrationTest`, `WebhookRollbackTest`, `WebhookSchemaContractTest`, `EndpointApiIntegrationTest`

## Tasks

1. Copy the context; rename packages, tables and constants; delete survey-only event filters; the event catalog is the ten
   schema files under `contracts/events/booking`.
2. Endpoint management: create (secret shown once), list, update with `If-Match`, rotate secret, disable, delivery log with
   manual redeliver. Permissions `booking:webhook:{read,manage}`.
3. Deliver once per subscribed endpoint per event id; retry with backoff as `core-webhook` does; the signature is
   `v1=` + HMAC-SHA256 of `timestamp + "." + rawBody`.
4. Private and loopback destinations are refused (`allowNonPublicTargets=false`) except an explicit setting used only by the
   compose smoke forwarder.
5. A publisher test per event type posts through the use case that emits it and validates the body against its schema.

## Verification

- Every webhook verifies with the endpoint's secret and matches its schema (`WebhookSchemaContractTest` enumerates all ten files and
  fails if one has no producing test).
- A use case that throws after appending an event leaves no delivery row.
- Two subscribed endpoints each receive one delivery; a 500 from one retries without duplicating the other.
- Another tenant's endpoint never receives the event.

## Risks

- The copy diverges from survey over time; accepted, since apps share contracts, not code, and `core-webhook` carries the mechanics.
