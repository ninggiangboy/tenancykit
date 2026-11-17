---
phase: 18
title: "Requester erasure"
status: pending
priority: P1
effort: "M"
dependencies: [7, 12, 16]
---

# Phase 18: Requester Erasure

## Goal

A Tenant can erase one person: their tickets, messages, attachments and every stored copy of their mail are removed, a tombstone
with no personal data remains, and the Tenant System is told with a signed webhook.

## Context

Customer and loyalty already expose an erasure endpoint (`/api/v1/customers/{externalRef}/erasure`,
`/api/v1/members/{externalRef}/erasure`) and workflow's erasure fan-out sample calls them; this adds helpdesk to the set. A
person's data sits in more places than the ticket row: messages, attachments and their objects, the raw inbound message kept for
30 days, the outbound mail ledger, SLA timers, conversations, participant lists on other tickets, ticket events, webhook
delivery bodies and the outbox. An erasure that misses one is worse than none, so the phase starts with an inventory.

## Files to create

- Migration `V13__erasure.sql`: `requesters.erased_at`, a tombstone constraint (no email, phone, name or contact reference once
  erased), `erasure_log` (tenant, requester, requested_by, started, completed), jOOQ regenerated
- `helpdesk/contexts/ticketing/.../erasure/**`: `ErasureInventory` (the list of every table and object prefix that can hold a
  requester's data), `EraseRequesterUseCase`, `ErasureJob` (worker, resumable), controller (`helpdesk:requester:erase`)
- Tests: `ErasureInventoryTest`, `EraseRequesterIntegrationTest`, `ErasureResumeTest`, `ErasureIsolationTest`,
  `ErasureWebhookTest`

## Tasks

1. **Inventory.** Write `ErasureInventory` as code plus a test that scans the jOOQ schema for any column or table that stores a
   requester, email address, name, body or object key and fails when one is not covered, so a later migration cannot silently
   leave a copy. Cover the object store by prefix `tenant/{tenantId}/helpdesk/{ticketId}/`.
2. **Endpoint.** `DELETE /requesters/{id}` (also by `externalRef` as a query on the collection) answers `202` with an erasure id;
   a repeat is idempotent. Permission `helpdesk:requester:erase`, grantable to a Tenant System.
3. **What it removes.** Tickets requested by the person, with their messages, attachments (objects first, then rows), inbound mail
   rows, outbound mail rows, timers, conversations, events, webhook delivery bodies that mention them; the person's address in
   other tickets' participant lists; messages they authored on a ticket someone else requested are replaced by a placeholder.
4. **Resumable.** The job works in bounded batches, records progress in `erasure_log`, survives a worker restart and reaches the
   same end state when run twice.
5. **Tombstone and webhook.** The requester row keeps its id and `externalRef`-free tombstone (so replays and idempotency keys
   stay meaningful) with `erased_at`; `requester.erased` carries the id, the erasure id and counts, never the data.
6. **Race.** A message arriving for an erased requester after completion creates a fresh requester row as for any unknown sender;
   the tombstone is never revived.

## Verification

- After erasure, a search over every table and the object prefix finds no trace of the person's address, name, body text or file.
- A restart in the middle of the job finishes the erasure with the same counts; a second call changes nothing.
- Tenant B cannot erase or even see tenant A's requester (`404`).
- The inventory test fails when a throwaway column storing an email is added to a copy of the schema.
- `requester.erased` validates against its schema and contains no personal data.

## Risks

- The outbox and the webhook delivery tables hold copies of payloads: they are in the inventory and are scrubbed, and
  `docs/helpdesk.md` states that a Tenant System that already received a webhook holds its own copy and must erase it itself.
