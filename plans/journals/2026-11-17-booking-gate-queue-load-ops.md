---
title: Booking public gate, waiting room, load and ops
date: 2026-11-17
summary: "Phases 13-16: the customer gate, ordered queue, invariants and the end-to-end smoke"
---

# Booking public gate, waiting room, load and ops

Implemented booking phases 13-16.

Gate: an opaque HMAC customer token binds tenant, customer, scopes and an optional offering. The guard filter binds the
tenant from the host because the ordinary host filter skips `/p/**`, then applies the body limit, honeypot, per-instance
rate bucket and registered-origin CORS before verifying the token. Spring Security's bearer resolver is disabled on the
public paths so a customer token is never decoded as a JWT. The surface is browse, hold, release and waitlist; there is
no confirm. Admission control caps in-flight holds per target.

Queue: tickets are insert-only and numbered from one sequence; a ticket token carries target and number, so a poll reads
the instance's cached watermark and never a ticket row. The admission job advances each target's watermark in ticket
order at its rate and expires admissions; one admitted ticket funds exactly one hold, and a failed hold returns the
ticket.

Invariants: the Java check and the owner SQL name each row that breaks a promise, and the load runner reports the status
histogram and p50/p95/p99 for `/p/bookings`.

Deviations recorded rather than hidden:

- The 100,000-attempt/5,000-place load run and the counter-row baseline were not executed in this environment. The
  runner and invariant script exist; the p95/p99 targets remain uncalibrated and `docs/booking.md` says so.
- `ops/smoke/booking.sh` was written and syntax-checked but not run against a compose stack: another workstream holds
  uncommitted compose changes. The compose-only webhook insecure-target setting for booking is therefore not added here.
- Admission evidence for 40 parallel gate holds over 20 places is in `PublicGateIntegrationTest` (all 20 succeed, the
  rest conflict or retry through a 429).
- A pool resize is reached by the waitlist's periodic scan rather than a synchronous wake-up row; the plan allowed either
  as long as a freed place is offered.

Evidence: `cd booking && ./gradlew check` green through phase 16; 320+ tests, including gate gates, token tamper,
CORS, queue order/one-ticket-one-hold, and invariant pass.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
