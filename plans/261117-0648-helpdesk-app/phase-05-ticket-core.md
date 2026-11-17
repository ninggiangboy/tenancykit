---
phase: 5
title: "Ticket core: requesters, queues, tickets, messages"
status: pending
priority: P1
effort: "L"
dependencies: [4]
---

# Phase 5: Ticket Core

## Goal

A Tenant System or an agent can create and list tickets, add public replies and internal notes, change status and priority,
and the history of every change is kept, all independent of any channel.

## Context

This is the channel-neutral model every later phase builds on. Money, SLA and mail are not involved. Numbers: each ticket has
a per-Tenant sequential `ticketNo` (a counter row updated in the creating transaction, never `max()+1`), because the thread
token and the subject token carry it. All writes take `If-Match` or `Idempotency-Key` as the other apps do; lists use the
shared list query contract (`core-query`).

## Files to create

- Migration `V2__tickets.sql`: `requesters` (tenant, `kind` EMAIL|PHONE|CHANNEL, normalised `email`, `phone`, `channel`,
  `contact_ref`, `name`, `external_ref`; unique `(tenant, email)` where email is set, unique `(tenant, channel, contact_ref)`,
  unique `(tenant, external_ref)`), `queues`, `tickets`, `ticket_counters`, `messages`, `ticket_events` (append-only audit of
  every change with actor), RLS policies on all, jOOQ regenerated
- `helpdesk/contexts/ticketing/src/{domain,application,api,adapter}/...`: `Ticket` aggregate with the status machine,
  `Message` with `direction`, `visibility`, `source`, `Requester`, `Queue`, use cases (create, reply, note, change status,
  change priority, patch), jOOQ repositories, controllers, mapping of `If-Match`
- Tests: `TicketStatusMachineTest`, `TicketCreationIntegrationTest`, `MessageVisibilityTest`, `TicketListQueryTest`,
  `TicketRowLevelSecurityTest`, `TicketNumberConcurrencyTest`

## Tasks

1. **Status machine.** `NEW` to `OPEN` on the first agent action or first customer reply after creation; `OPEN` to `PENDING`
   (agent waits for the customer), `PENDING` and `SOLVED` back to `OPEN` on a customer reply, `OPEN` or `PENDING` to `SOLVED`,
   `SOLVED` to `CLOSED` (agent or auto-close), `CLOSED` terminal. Illegal moves answer `409` with a stable code.
2. **Creation.** `POST /tickets` takes channel, queue, subject, requester (email, phone, channel reference, `externalRef`),
   priority (`LOW`, `NORMAL`, `HIGH`, `URGENT`), optional first message. A requester is found or created by its natural key;
   email is lower-cased and trimmed; a conflicting `externalRef` is `409`.
3. **Messages.** A reply is `visibility=PUBLIC` and `direction=OUTBOUND`; a note is `INTERNAL`. A note never leaves the
   system and never appears on a public surface. Message bodies are stored as `text` plus an optional sanitised `html`.
4. **Queues.** `queues` carry `name`, `address_local` (for email routing), default priority, default SLA policy id and default
   group id (filled by later phases); a `default` queue exists per Tenant created lazily.
5. **Reads.** `GET /tickets` filters by status, queue, assignee, channel, priority, requester, `externalRef`, created range;
   `GET /tickets/{id}/messages` pages in time order; `GET /tickets/{id}/events` returns the audit trail.
6. **Requester patch.** `PATCH /requesters/{id}` sets name and `externalRef` and nothing else.
7. **Authorisation.** Read needs `helpdesk:ticket:read`, create `ticket:create`, public reply `ticket:reply`, note
   `ticket:note`, status and priority `ticket:status`.

## Verification

- 100 parallel creations for one Tenant produce 100 distinct consecutive `ticketNo` values with no gap and no duplicate.
- Every illegal transition is `409`; every legal one writes one `ticket_events` row with the actor.
- A tenant B principal reading tenant A's ticket, message, requester or queue gets `404`; the row-level security test covers
  every table.
- A note is absent from every response that a public caller could ever receive (asserted again in phase 13).
- `If-Match` with a stale ETag is `412`; a repeated `Idempotency-Key` returns the first response.

## Risks

- A ticket number gap is cosmetic but a duplicate breaks threading; the counter increment shares the ticket insert's
  transaction and the concurrency test is the guard.
