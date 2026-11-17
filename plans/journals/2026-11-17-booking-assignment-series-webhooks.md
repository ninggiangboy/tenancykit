---
title: Booking assignment, series and signed webhooks
date: 2026-11-17
summary: "Phases 9-11: multi-resource assignment, reschedule and series, signed webhook delivery"
---

# Booking assignment, series and signed webhooks

Implemented booking phases 9-11.

Assignment: a requirement is filled by a resource or a group, whose strategy
is PRIORITY, ROUND_ROBIN, LEAST_BUSY or FIT_SEATS. A seat-limited group may
fill one slot with the smallest-waste combination of tables. Claims are
inserted in ascending resource id, so two hundred bookings over crossing
requirement sets finish as holds and conflicts with no deadlock. The
round-robin cursor lives on the group row and advances in the booking's
transaction.

Lifecycle: reschedule keeps the id and status, releases the old claims and
takes the new place in one transaction; a shift inside its own old range
works. A series stores the Tenant's RRULE and creates one hold per
occurrence, all-or-nothing or skipping conflicts, expanded in the zone of
its local start so a DST change keeps the wall-clock time. A cancellation of
a confirmed booking records the offering's window code, and a missed one is
marked after its start with the claims kept.

Webhooks: the booking webhook context is a copy of the suite's, with
`Booking-*` headers and `booking.webhook:{read,manage}`. The outbox relay
runs in process; one delivery is queued per subscribed endpoint in the
event's transaction, the sender claims with `SKIP LOCKED`, signs
`timestamp.body` with the endpoint's secret and retries with backoff. A
rolled-back use case leaves neither an outbox row nor a delivery. Endpoints
return their secret once, require `If-Match` to change, and a non-public
destination is refused unless the deployment opts in.

Evidence: `cd booking && ./gradlew check` green; 303 tests, forced rerun.
Phase 9 adds `MultiResourceAssignmentTest` and
`CrossingResourceSetsDeadlockTest`; phase 10 adds reschedule, series, policy
and no-show tests; phase 11 adds endpoint, delivery, rollback and schema
contract tests. The two waitlist event schemas are validated as contracts;
phase 12 will add their producing use cases and replace the example checks.

Next: phase 12 (waitlist).

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
