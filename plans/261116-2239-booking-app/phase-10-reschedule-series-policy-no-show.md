---
phase: 10
title: "Reschedule, series, cancellation policy and no-show"
status: completed
priority: P1
effort: "L"
dependencies: [9]
---

# Phase 10: Reschedule, Series, Cancellation Policy and No-Show

## Goal

A booking can move atomically, a customer can book a repeating series in one call, a cancellation records which policy window it
fell in, and a missed appointment is marked, all as recorded facts with no money.

## Context

Reschedule keeps the booking id and changes where it is claimed, in one transaction: either the booking is at the new place or it is
unchanged. For the interval engine a shift can overlap the booking's own old range, so the old claims are removed and the new ones
inserted inside the same transaction (the exclusion constraint never sees both). Policy windows were stored in phase 4 as
`(minutes_before_start, code)`. Booking never charges a fee; it reports the code and the Tenant acts.

## Files to create

- Migration `V9__series_and_policy.sql`: `series(tenant_id, id, offering_id, customer_ref, recurrence lines, zone, mode, status)`,
  `bookings.series_id` already exists, add `bookings.no_show_at`; regenerate jOOQ
- `booking/contexts/booking/...`: `RescheduleBookingUseCase`, `CreateSeriesUseCase`, `CancelSeriesUseCase`, `CancellationPolicy`,
  `MarkNoShowUseCase`, controllers
- Events `booking.rescheduled`, `booking.no-show`, `series.created`, `series.cancelled` via the `BookingEvents` port
- Tests: `RescheduleTest`, `SeriesTest`, `CancellationPolicyTest`, `NoShowTest`

## Tasks

1. **Reschedule.** `POST /bookings/{id}/reschedule` with the new session or start (and resources for interval): validate the booking
   is `HELD` or `CONFIRMED`, claim the new place and release the old in one transaction, keep `status`; a confirmed booking stays
   confirmed (claims are created confirmed). On `slot-taken` or `sold-out` nothing changes. Appends `booking.rescheduled` with the old
   and new place. Capacity: claim new units first, then release old ones.
2. **Series create.** `POST /series` with `offeringId`, `customerRef`, a recurrence (RRULE lines, bounded by `COUNT`/`UNTIL`, at most
   52 occurrences by default), start local time, duration/pool, and `mode`. `ALL_OR_NOTHING` (default) claims every occurrence in one
   transaction and answers `409` listing each failing occurrence and reason; `SKIP_CONFLICTS` books what it can and returns
   `skipped[]`. Every occurrence is a `HELD` booking with `series_id` and a shared hold expiry.
3. **Series confirm and cancel.** `POST /series/{id}/confirm` confirms all `HELD` occurrences in one transaction.
   `POST /series/{id}/cancel` cancels all future occurrences; cancelling one booking leaves the others.
4. **Cancellation policy.** On cancel of a `CONFIRMED` booking, find the first window (descending `minutes_before_start`) whose
   threshold is ≤ the minutes remaining before start, store its `code` as `policy_code`, and send it in `booking.cancelled`; with no
   windows the code is `NONE`; after the start the code is `AFTER_START`. Cancelling a `HELD` booking always records `NONE`.
5. **No-show.** `POST /bookings/{id}/no-show` is allowed on a `CONFIRMED` booking once its start has passed; sets `NO_SHOW`, keeps the
   claims as history, appends `booking.no-show` with policy code `NO_SHOW`.
6. **Permissions.** Reschedule and cancel of a confirmed booking are `booking:booking:manage`; series use `booking:series:*`.

## Verification

- Reschedule to a taken place leaves the booking and its claims byte-for-byte unchanged; a shift by 15 minutes inside its own range
  succeeds; 50 parallel reschedules into one free place leave exactly one winner and release the others' old places only on success.
- A weekly series for 8 weeks across a DST change books each occurrence at the same local time; with one occurrence blocked, the
  default mode creates nothing and lists that occurrence, `SKIP_CONFLICTS` books seven and lists one.
- Policy tests for each window boundary, no windows, after start, and a held booking.
- No-show before the start is `409 booking-state`.

## Risks

- Reschedule of a series member: only that occurrence moves and keeps its `series_id`; the series is not rewritten. Documented.
