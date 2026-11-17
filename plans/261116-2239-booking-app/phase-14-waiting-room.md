---
phase: 14
title: "Waiting room: ordered admission queue"
status: completed
priority: P1
effort: "L"
dependencies: [13]
---

# Phase 14: Waiting Room: Ordered Admission Queue

## Goal

For an offering sold in a rush, customers entering through the gate receive an ordered ticket, are admitted in ticket order at a rate
the Tenant sets, and only an admitted customer may hold; waiting costs the database almost nothing however many people poll.

## Context

The user chose an ordered queue stored in Postgres over per-instance admission control alone (decision of the planning validation).
Per-instance admission control from phase 13 stays as the inner cap that protects the connection pool; this phase adds fairness on
top. The queue must not recreate the hot row the capacity engine avoids, so tickets are insert-only and admission advances a single
watermark row per target that only the worker writes. Polling reads an in-memory copy of that watermark, refreshed at most once per
second per instance, never a per-ticket row.

## Files to create

- Migration `V13__queue.sql`: sequence `queue_ticket_seq`; `queue_tickets(tenant_id, id, target_id, ticket_no, customer_ref, status
  WAITING|ADMITTED|USED|EXPIRED|LEFT, issued_at, admitted_at)` with index `(target_id, status, ticket_no)` and RLS;
  `queue_state(tenant_id, target_id, watermark_ticket_no, updated_at)`; offering columns `queue_mode NONE|REQUIRED`,
  `admit_per_second`, `admit_window_seconds`; regenerate jOOQ
- `booking/contexts/gate/.../queue/**`: ticket issue, ticket token, watermark cache, status, leave, hold-with-ticket guard
- `booking/apps/app-boot/.../jobs/QueueAdmissionJob.java`, `QueueRetentionJob.java`
- Tenant API: `GET /offerings/{id}/queue` (waiting, admitted, used, expired counts) under `booking:offering:read`
- Tests: `QueueOrderTest`, `QueueAdmissionJobTest`, `QueueTicketUseTest`, `QueuePollingLoadTest`, `QueueIsolationTest`

## Tasks

1. **Settings.** Offering `queue_mode` (`NONE` default), `admit_per_second` (default 50), `admit_window_seconds` (default 120), set
   through the offering API (`booking:offering:manage`).
2. **Issue.** `POST /p/queue` with the offering and, for capacity, the session; the customer token's `hold` scope is enough. Insert
   a ticket with `nextval('queue_ticket_seq')` (insert only, no counter row) and return a signed ticket token (target, ticket number,
   customerRef, expiry, key id; same key set as customer tokens) and a first `retryAfter`. One active ticket per `(customerRef, target)`.
3. **Admission job.** Each second per target that has `WAITING` tickets (lease-guarded like other jobs): admit the next
   `admit_per_second × elapsed` tickets in ticket order by one batch update (`ADMITTED`, `admitted_at`), set `watermark_ticket_no` to
   the highest admitted number in the same transaction, and expire `ADMITTED` tickets older than the window. Admission order equals
   issue order.
4. **Poll.** `GET /p/queue/status` with the ticket token: verify the signature, read the instance's cached watermark, answer
   `admitted` iff `ticket_no <= watermark`, otherwise `position = ticket_no - watermark` (an upper bound, because ticket numbers are
   shared across targets) and `retryAfter` from `position / admit_per_second`, clamped to 1-30 seconds with jitter. No per-ticket row is
   read.
5. **Hold with ticket.** When `queue_mode = REQUIRED`, `POST /p/bookings` must carry the ticket token: `403 queue-required` without it,
   `403 not-admitted` when the ticket is not `ADMITTED`, `403 admission-expired` after the window. A successful hold marks the ticket
   `USED` in the same transaction with a conditional update (a ticket funds one hold); a failed hold (for example `sold-out`) leaves it
   `ADMITTED` so the customer may retry until the window ends.
6. **Leave.** `DELETE /p/queue` with the ticket marks it `LEFT`, and the job skips it.
7. **Retention.** A daily job deletes tickets older than 24 hours; tickets hold only a `customerRef`.
8. **No webhook.** A ticket is not a fact the Tenant acts on; the resulting hold already sends `booking.held`.

## Verification

- With 10,000 tickets and an admit rate of 100 per second, tickets are admitted in issue order (property test over several runs); the
  job restarting mid-run neither skips nor repeats a ticket.
- A customer without a ticket, with a `WAITING` ticket, with an expired admission, or with another customer's ticket cannot hold; one
  ticket funds exactly one hold under 50 parallel attempts.
- 20,000 simulated clients polling for 10 seconds cause at most one `queue_state` read per second per instance per target, asserted by
  a query counter, and no `queue_tickets` read.
- Tenant B's tickets, counts and state are invisible to tenant A.

## Risks

- The queue is a second moving part on the hottest path; if it proves too costly, the offering's `queue_mode = NONE` falls back to
  per-instance admission control alone with no migration.
- `position` is an upper bound and the cached watermark can be up to one second stale; both are documented, since the queue promises
  order, not an exact rank.
