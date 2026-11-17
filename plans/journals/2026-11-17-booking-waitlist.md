---
title: Booking waitlist
date: 2026-11-17
summary: "Phase 12: joining a full target, offers through the normal hold path, and lapse to the next entry"
---

# Booking waitlist

Implemented booking phase 12.

An entry targets a capacity session's pool or an interval offering's range. Joining is refused when the
target has a free place (`422 TARGET_AVAILABLE`), one customer has one `WAITING` entry per
target (`409 ALREADY_WAITING`), and the target's line has the tenant's cap (`409 waitlist-full`).

A release (cancel, expiry, reschedule) writes a wake-up row in its own transaction. The worker claims
wake-ups outside it and runs one matcher pass per target; it also scans waiting targets each poll so a
place freed without a wake-up, such as a pool resize, still reaches the line. The offer is an ordinary
`HELD` booking through `CreateBookingUseCase` with source `WAITLIST` and the offer window as its TTL;
the entry points at that booking and follows it. Confirming accepts the entry; expiry or cancellation
lapses it, appends `waitlist.lapsed` with the reason and writes a new wake-up.

An entry that does not fit stays `WAITING` and keeps its position while the next entry is offered. A
direct hold may take a freed place before the pass, which is documented as the boundary of the
waitlist's fairness.

Evidence: `cd booking && ./gradlew check` green. `WaitlistIntegrationTest` covers join, leave,
position, an accepted offer, a lapsed offer offering the next entry, and the 3-place entry skipped for
a 1-place one. `WaitlistWebhookTest` posts the two events through the use cases and validates their
outbox payloads against the committed schemas.

Next: phase 13 (the public gate).

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
