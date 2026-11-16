---
phase: 5
title: "Recurrence, time and session materialization"
status: completed
priority: P1
effort: "L"
dependencies: [4]
---

# Phase 5: Recurrence, Time and Session Materialization

## Goal

Full RFC 5545 recurrence expands correctly in a resource's zone across DST, and a capacity offering's repeating sessions exist as
rows inside a rolling horizon, with their places created.

## Context

No RRULE library is declared in the repo. Candidates to try are ical4j and the dmfs recurrence library; the choice is made by the
vectors from phase 1, not by reputation, and recorded in `docs/booking.md`. Expansion is a port,
`RecurrenceExpander(rule lines, zone, window) -> instants`, with one adapter; nothing else in booking names the library. Instants
are stored UTC; local times are resolved with `ZonedDateTime` in the resource's zone (a nonexistent local time moves forward by the gap, an
ambiguous one takes the first occurrence, as RFC 5545 says).

## Files to create

- Migration `V4__recurrence_and_sessions.sql`: `blocks`, `session_series`, `sessions`, `session_pools`; regenerate jOOQ
- `booking/contexts/catalog/src/{domain,application,adapter}/.../recurrence/**`: port, adapter, validator
- `booking/contexts/catalog/.../session/**`: series, session, pool features, materialization use case
- `booking/apps/app-boot/.../jobs/SessionHorizonJob.java`
- Tests: `RecurrenceVectorTest` (reads `contracts/booking/recurrence-vectors/*.json`), `RecurrenceDstTest`,
  `SessionMaterializationTest`, `SessionSeriesApiIntegrationTest`

## Tasks

1. **Spike (time-boxed).** Write the vector runner first. Run both candidate libraries against every vector, including BYSETPOS,
   EXDATE/RDATE, nth weekday, and the three DST zones. Pick the library that passes all of them with the fewest workarounds;
   record the result and the versions in the journal. If neither passes a vector, decide whether the vector or a thin adapter
   fix is wrong before considering a third library.
2. **Validator.** Reject a rule that is unparsable, has no `COUNT`/`UNTIL` and no bounded window, expands to more than the
   per-call cap (default 10,000 occurrences), or uses `FREQ` finer than daily for sessions. Typed `422 invalid-recurrence` with the
   offending part.
3. **Blocks.** `blocks(resource_id, recurrence lines or one-off range, duration, reason)`: closed periods (maintenance, holiday).
   Availability (phase 6) subtracts expanded blocks; blocks never delete existing bookings and report the bookings they overlap.
4. **Session series.** `session_series(offering_id, recurrence lines, zone, start local, duration, capacity, pool codes)` and
   `sessions(series_id null, offering_id, starts_at, ends_at, status SCHEDULED|CANCELLED, capacity)`; a one-off session has no series.
   `session_pools(session_id, code, capacity)` with a `default` pool when none is named.
5. **Materialization.** On create and daily in the worker, expand each active series to the horizon and insert missing sessions
   idempotently (unique `(series_id, starts_at)`); the places themselves are created by phase 7 through a port this phase defines
   and phase 7 implements. Changing a series regenerates only future sessions with no booking; the response lists sessions it left alone.
6. **Cancel one occurrence.** `DELETE /sessions/{id}` marks it `CANCELLED` and adds an EXDATE to the series so it is not re-created.
7. **Permissions.** `booking:calendar:manage` for blocks and series; reads under `booking:calendar:read`.

## Verification

- `RecurrenceVectorTest` passes every vector for the chosen library; the same vectors pass through the HTTP endpoint that
  previews an expansion (`POST /recurrence/preview`).
- A weekly 09:00 session across a spring-forward in New York keeps 09:00 local and moves the UTC instant by one hour; the same in
  London; Lord Howe moves by 30 minutes.
- Running the horizon job twice creates no duplicate session; an EXDATE'd occurrence is not re-created.
- Changing a series that has a booking on one future session keeps that session and reports it.

## Risks

- This is the longest tail of edge cases. Keep the expander behind its port, keep the vectors in `contracts/`, and add a
  regression vector for every bug found.
