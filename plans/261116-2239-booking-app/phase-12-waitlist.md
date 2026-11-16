---
phase: 12
title: "Waitlist"
status: pending
priority: P1
effort: "L"
dependencies: [8, 11]
---

# Phase 12: Waitlist

## Goal

When a session or range is full, a customer can join a waitlist; when a place frees, the next entry that fits receives it as a short
hold and the Tenant is told by webhook; if the hold lapses, the next entry is tried.

## Context

An offer is not a new lifecycle: the worker creates a `HELD` booking with source `WAITLIST` through the normal hold use case, with
the offer window as its TTL, and the entry follows that booking's fate. A place frees by cancel, expiry, resize, or reschedule (phase
8 and 10). To keep releases cheap, a release records a wake-up row; the worker processes wake-ups outside the releasing transaction.

## Files to create

- Migration `V11__waitlist.sql`: `waitlist_entries(tenant_id, id, offering_id, customer_ref, session_id null, pool_code null, desired
  starts_at null, desired ends_at null, qty, resource selector null, status WAITING|OFFERED|ACCEPTED|LAPSED|CANCELLED,
  offered_booking_id, offered_until, created_at)` and `waitlist_wakeups(tenant_id, id, scope, created_at)`; regenerate jOOQ
- `booking/contexts/booking/.../waitlist/**`: entry aggregate, matcher, offer use case, controller
- `booking/apps/app-boot/.../jobs/WaitlistJob.java`
- Events `waitlist.offered`, `waitlist.lapsed` via `BookingEvents`
- Tests: `WaitlistJoinTest`, `WaitlistOfferTest`, `WaitlistFairnessTest`, `WaitlistLapseTest`

## Tasks

1. **Join.** `POST /waitlist` with `offeringId`, `customerRef`, `qty` and either a session and pool (capacity) or a desired range and
   resource selector (interval). Allowed only when the target is not free; one `WAITING` entry per `(customerRef, target)`;
   per-target length cap in settings (`409 waitlist-full`).
2. **Wake-ups.** Cancel, expiry, resize-up and reschedule write a wake-up row for the target in their own transaction.
3. **Matcher.** `WaitlistJob` takes wake-ups under `TenantSweep`, loads `WAITING` entries for the target oldest first, and for each
   tries the hold use case with `source = WAITLIST` and `ttl = offer window`; the first success marks the entry `OFFERED` with
   `offered_booking_id` and appends `waitlist.offered`; an entry that does not fit stays in place and keeps its position.
4. **Entry follows booking.** Booking `CONFIRMED` makes the entry `ACCEPTED`; booking `EXPIRED` or `CANCELLED` makes it `LAPSED`,
   appends `waitlist.lapsed` and writes a new wake-up so the next entry is tried.
5. **Leave and position.** `DELETE /waitlist/{id}`; `GET /waitlist/{id}` returns status and the count of `WAITING` entries ahead.
6. **Permissions.** `booking:waitlist:{read,manage}`; the gate (phase 13) offers join, leave and position to a customer for its own
   `customerRef` only.

## Verification

- With a full 2-place session and three waiting entries, one cancellation offers the oldest entry a hold for the offer window and sends
  `waitlist.offered`; confirming it ends `ACCEPTED`; letting it lapse sends `waitlist.lapsed` and offers the next entry.
- An entry for 3 places is skipped when 1 place frees and keeps its position; a later single-place entry is offered.
- 50 parallel wake-ups for one target offer each freed place exactly once.
- No offer is made for a place that a direct hold took first (the hold use case's conflict path leaves the entry `WAITING`).

## Risks

- Fairness under concurrent direct holds is best effort: a direct hold can take a freed place before the job runs. Documented; the
  waitlist guarantees order among waiters, not priority over direct callers.
