---
title: "Booking app — generic appointments, rentals, capacity sessions and multi-resource holds"
description: "An API-only booking/ deployable with two inventory engines (capacity: one row per place; interval: exclusion constraint on time ranges) behind one hold-confirm lifecycle, full RFC 5545 recurrence, waitlist, signed Booking-* webhooks and a public customer gate with anti-bot; a Tenant System confirms after its own payment and never shares code with another app."
status: pending
priority: P1
effort: "16 phases, 4 × M, 12 × L"
branch: dev
tags: [feature, backend, api, database, infra]
blockedBy: []
blocks: []
created: 2026-11-16
---

# Booking App

## Overview

A Tenant that sells time or places (a salon, a clinic, a rental, a class, a restaurant, an event) has no suite app that
keeps what may be booked, when it is free, and who holds it. This plan adds `booking/`, an API-only deployable that joins
the suite the way survey, crm and loyalty did: its own build and database, a Platform replica, an entitlement, a
permission catalog and signed webhooks. Platform and Billing do not change.

Scope was fixed in the `/ak-advise` interview of 2026-11-16 (memory: `booking-app-decisions`; preview:
`plans/reports/advise-261116-2217-booking-app-preview.html`). The user's heavier choices are kept and priced in the risk
register: full RRULE, a public customer gate in v1, flash-sale load, waitlist, series, reschedule, any-available assignment.

Shape in one paragraph. Two inventory engines sit behind one port. The **capacity engine** keeps one row per bookable
place (a ticket, a class seat) and claims rows with a conditional `UPDATE ... FOR UPDATE SKIP LOCKED`; it carries the
flash-sale guarantee. The **interval engine** keeps one row per claimed time range on a resource and refuses overlap with a
PostgreSQL exclusion constraint; it serves appointments, rentals and tables. One `Booking` aggregate (states `HELD`,
`CONFIRMED`, `CANCELLED`, `EXPIRED`, `NO_SHOW`) drives both. "Hold" is the `HELD` state with an expiry; the word
"reservation" is Billing's and is not used here. Booking never touches money: the Tenant holds, charges through Billing,
then confirms.

## Outcome contract

**Goals**

1. A Tenant System defines resources, resource groups, offerings, weekly hours, exceptions and recurring sessions, searches
   free time or free places, holds, confirms, extends, cancels, reschedules and marks no-shows, through one API.
2. The same configuration model serves an appointment (staff + service + slot), a rental (free range on one resource), a
   capacity session (N places, repeating weekly) and a multi-resource booking (staff + room, or a merged table), with no
   code change per business type.
3. Nothing is booked twice: no resource has two overlapping claims, and a capacity pool never holds or sells more than its
   capacity, at any concurrency.
4. A Tenant's customer reaches booking only through a public gate with a short-lived customer token the Tenant asked for;
   the gate can browse, hold, release its own hold and join the waitlist, never confirm.
5. A freed place goes to the next person on the waitlist for a short window and the Tenant is told by webhook.
6. Every fact a Tenant may act on leaves booking as a signed webhook; no suite app binds to booking and booking calls no app.

**Done means**

- `cd booking && ./gradlew check` is green with the ArchUnit, Modulith and isolation gates.
- `git diff` of the whole delivery shows no Java change under `platform/`, `billing/`, `mailer/`, `notification/`,
  `mailroom/`, `survey/`, `attribution/`, `crm/`, `loyalty/`, `tenant/` or `core/`. Platform changes only `application.yml`
  (one service-client entry) and picks up `contracts/permissions/booking.yaml` at build time.
- `git grep -n -i -E "survey|mailroom|attribution|crm|loyalty|billing" -- booking/apps booking/contexts booking/libs` finds
  no import, call, queue or table naming another app; the only suite names in booking are Platform and `core`.
- `contracts/openapi/booking.yaml`, `contracts/permissions/booking.yaml`, `contracts/events/booking/*.schema.json` (ten
  webhook payloads) and `contracts/booking/recurrence-vectors/*.json` exist, and contract tests prove the running app matches
  them; a Tenant's RRULE vectors pass through the real endpoints.
- Concurrency tests prove: N parallel holds on a pool of M places create exactly M holds and zero oversell (N ≥ 10 × M,
  repeated); two parallel holds on overlapping ranges of one resource leave exactly one; a multi-resource hold either claims
  every resource or none; 100 parallel confirms of one booking succeed once; confirm racing expiry yields one winner and
  leaves no orphan claim; the same `Idempotency-Key` yields one booking.
- Time tests prove: weekly hours, exceptions and recurring sessions are correct across a DST gap and overlap in at least
  three IANA zones; every vector in `contracts/booking/recurrence-vectors/` expands identically through the library and the
  endpoints.
- Lifecycle tests prove: reschedule moves a booking atomically (new claims and old release in one transaction) and leaves the
  booking unchanged when the new place is taken; a series is all-or-nothing by default and reports the conflicting occurrences otherwise; a
  cancellation records the policy window outcome and sends it; any-available picks by the group's strategy and falls through
  to the next candidate on conflict; a waitlist offer is a `HELD` booking owned by the next entry and moves on when it lapses.
- Gate tests prove: a missing, expired, wrong-tenant or wrong-scope customer token is `401`/`403`; the gate refuses `confirm`;
  the rate limit answers `429` with `Retry-After`; a filled honeypot or oversize body is refused; CORS allows only the
  origins the Tenant registered; the tenant always comes from the host.
- Each of the ten webhook types is delivered once per subscribed endpoint, signed with `Booking-*` headers, matches its
  schema, and a transaction that rolls back sends nothing.
- Waiting-room tests prove: tickets are admitted in issue order at the set rate across a worker restart; a customer without an
  admitted, unexpired ticket of its own cannot hold on a `REQUIRED` offering; one ticket funds one hold under parallel use; 20,000
  polling clients cause at most one `queue_state` read per second per instance per target and no ticket-row read.
- `ops/loadtest/booking.sh` reports, through the public gate and the waiting room, 100,000 customers against a 5,000-place
  session ending with exactly 5,000 holds, prints p95 and p99 for hold, and shows the run without the queue and the counter-row
  baseline beside it.
- `ops/smoke/booking.sh` on the compose stack plays the Tenant System: create offering, enter the queue and hold through the
  gate, confirm, receive the signed webhook, fill a session, join the waitlist, cancel and receive the offer. `ops/smoke/isolation.sh`
  proves the booking broker user can neither publish to nor bind to another app's exchange.
- `docs/booking.md` exists and `docs/README.md`, `docs/overview-and-scope.md`, `docs/suite-messaging.md`,
  `docs/runtime-profiles.md` and the root `README.md` count the app; `git grep -n -i "booking" -- docs/overview-and-scope.md`
  shows the scope and non-goals recorded.

**Non-goals**

Stripe or any money, prices and fee amounts in booking; seat maps, named seats and seat selection; tickets, QR codes and
check-in; any UI; external calendar sync (Google or iCal feeds); storing any personal data of a customer (a customer is an
opaque `customerRef` the Tenant chooses); booking sending any message itself; a booking AMQP exchange; any call to or from
another suite app; CAPTCHA from a third party; Redis or any store beyond PostgreSQL and RabbitMQ; multi-currency; integrating booking into the sample Tenant System (a later piece of work); any code
change in an existing app or in `core/`.

**Constraints**

Tenant always from the host, never from client input; instants stored as UTC `timestamptz` and each resource carries its own
IANA zone; recurrence is expanded in the resource's zone; use a library for RRULE, never a hand-written parser; jOOQ with a
committed generated schema, RLS, suite tokens, ETag and `If-Match`, the shared list query contract on every list endpoint,
`core/` shared modules, api/worker/migrate runtimes, in-process outbox feeding webhooks; conventional commits without AI
references; no plan or phase number in code, migration or test names; the Tenant System is the only integrator.

## Evidence from the scout (verified in source on 2026-11-16)

| Finding | Source | Consequence |
|---|---|---|
| Survey already has a public surface with an HMAC-signed, key-rotated link token, a guard filter ahead of authentication, a per-instance token-bucket limiter keyed by an HMAC of the IP, a honeypot and a permit-all matcher for `/p/**` and `/survey/p/**` | `survey/apps/app-boot/.../security/SecurityConfiguration.java`, `survey/contexts/collection/.../guard/{web/PublicGuardFilter,service/PublicRateLimiter,service/Honeypot,service/ClientFingerprint}.java`, `survey/apps/app-boot/.../config/LinkConfiguration.java` | The gate copies the pattern (separate build, no shared code beyond `core/`); the customer token is an HMAC token, not a JWT, so no JWKS is needed |
| The per-instance limit is documented as N times the setting with N instances | `PublicRateLimiter` javadoc | Admission control is per instance too; a shared store is not added |
| Suite JWT verification is Platform ES256 plus a Keycloak realm, per app audience | `core/core-suite/.../security/SuiteJwtDecoders.java` | The Tenant System API uses it unchanged; booking mints no JWT |
| Survey keeps a replica context, an authoring/collection/insight split and a `webhook` context whose `OutboxRelay` feeds deliveries in process; no exchange is needed | `ls survey/contexts`, `survey/apps/app-boot/.../config/WebhookConfiguration.java` | Booking copies `platform` and `webhook` contexts; it declares no exchange (suite rule) |
| `TenantSweep` runs per-tenant jobs under RLS with bounded virtual threads | `survey/apps/app-boot/.../jobs/TenantSweep.java` | Hold expiry, session materialization and waitlist offers are sweep jobs |
| Core defines per-app webhook header sets (`BILLING`, `SURVEY`) as record constants; the record constructor is public | `core/core-webhook/.../WebhookHeaderSet.java`, `core/core-kernel/.../http/HttpHeaders.java` | Booking builds its `Booking-*` set inside its own build; no core change. Phase 1 confirms how crm did it and follows |
| No RRULE or iCalendar library is declared anywhere in the repo | `git grep -i -E "ical4j|rfc5545|lib-recur|rrule" -- '*.toml' '*.kts' '*.gradle'` is empty | Phase 5 opens with a time-boxed spike that chooses the library by the vectors, not by reputation. A web search for current releases returned nothing usable |
| Migrations already create extensions with `create extension if not exists pg_trgm`; Postgres is 18 | `survey/.../V7__list_query_indexes.sql:7`, `docker-compose.yml:151` | `btree_gist` is created the same way; phase 3 proves the owner role can create it on the compose database |
| Roles, databases, broker user, Caddy route and Platform service client follow one fixed set of touchpoints | `git grep -l -i survey -- . ':!survey' ':!plans' ':!docs'` | Phases 2 and 15 edit that list, re-grepped at execution time |
| Survey and attribution are in flight (attribution has uncommitted changes under `contracts/openapi/attribution.yaml`); crm and loyalty are in progress | `git status`, plan statuses | Shared ops, compose and contracts files are edited append-only and re-read before each edit; this plan never edits another app's tree |
| `RepeatableCreate`, `Fingerprint`, `ConditionalUpdate`, `ExpectedVersion`, the list query contract (`core-query`) and `core-visitor` exist | `core/core-kernel`, `core/core-query`, `core/core-visitor` | Idempotency, ETag, paging and client fingerprint reuse them; no new core code |
| The sample Tenant System integrates Billing only | `git grep -l -i -E "survey|mailroom|attribution|loyalty|crm" -- tenant` is empty | End-to-end proof is a smoke script playing the Tenant |

## Key decisions

- **Two engines, one port.** `InventoryEngine` has two concrete implementations (capacity, interval) and no registry. The
  port is capability-aware: only the capacity engine promises flash-sale throughput; the interval engine promises no overlap.
  Extension trace: the next credible variant is seat selection for the capacity engine (named units); it adds a label and a
  layout reference to `capacity_units` and leaves the lifecycle, the interval engine and the webhooks unchanged. It is
  speculation and nothing is built for it.
- **One aggregate.** `Booking` with states `HELD → CONFIRMED`, `HELD → EXPIRED|CANCELLED`, `CONFIRMED → CANCELLED|NO_SHOW`.
  The API creates it with `POST /bookings` (it starts `HELD`), so "hold" is a state and not a second resource.
- **Claims are rows, not counters.** Capacity: one `capacity_units` row per place. Interval: one `interval_claims` row per
  resource and range with `EXCLUDE USING gist (resource_id WITH =, during WITH &&)`; releasing deletes the row. A claim first
  removes the same resource's expired holds in the same transaction, because the constraint cannot see `expires_at`.
- **Lock order.** Booking row, then resources by ascending id, then units by ascending id. Written in `docs/booking.md` next to
  the lock safety net it follows.
- **Confirm after payment.** Confirm succeeds while the booking is still `HELD` and its claims are still held, even a little
  past `holdExpiresAt` (a grace window reclaimed only by the worker); `extend` lets a Tenant lengthen a hold once, up to a
  cap, before it charges. Confirm and expiry race on a conditional `UPDATE ... WHERE status = 'HELD'`.
- **Waitlist offer is a hold.** The worker creates a `HELD` booking for the next entry; the entry follows that booking's
  fate. No second lifecycle.
- **Recurrence.** Sessions and recurring blocks are RRULE-driven, expanded in the resource zone. Capacity sessions are
  materialized into rows inside a rolling horizon (default 120 days) by a daily job and on create; interval availability is
  computed at query time from weekly hours, exceptions and blocks minus claims, and is never materialized.
- **Customer token.** HMAC-signed opaque token with a key id and a short lifetime, bound to tenant, `customerRef`, scopes and
  optionally one offering; the Tenant System asks booking to mint it. Booking holds the keys; rotation follows survey.
- **Waiting room = ordered queue in PostgreSQL plus per-instance admission control** (the user chose the ordered queue over
  admission control alone at validation). Tickets are insert-only rows numbered from a sequence, so no counter row is hot; a worker
  admits tickets in order at the offering's rate and advances one watermark row per target; polling reads an in-memory copy of that
  watermark and never a ticket row; a hold needs an admitted ticket when the offering sets `queue_mode = REQUIRED`. Per-instance
  admission control stays as the inner cap that protects the connection pool. `position` is an upper bound.
- **Waitlist priority.** The waitlist guarantees order among waiters only; a direct hold may take a freed place first (best effort,
  documented). Chosen at validation.
- **Default timings (reversible, in settings).** Hold 10 minutes, extend up to 30 minutes total, grace 60 seconds, waitlist
  offer window 10 minutes, token lifetime up to 1 hour, rate limit 120 requests per minute per fingerprint.

## Phases

| # | Phase | Size | Depends on |
|---|---|---|---|
| 1 | [Scope, docs, contracts and decisions](phase-01-scope-docs-contracts-and-decisions.md) | M | none |
| 2 | [Identity and ops seams](phase-02-identity-and-ops-seams.md) | M | 1 |
| 3 | [Build skeleton, replica and gates](phase-03-build-skeleton-and-gates.md) | L | 1, 2 |
| 4 | [Catalog: resources, groups, offerings and calendars](phase-04-catalog-resources-offerings-calendars.md) | L | 3 |
| 5 | [Recurrence, time and session materialization](phase-05-recurrence-time-and-sessions.md) | L | 4 |
| 6 | [Interval engine and availability](phase-06-interval-engine-and-availability.md) | L | 5 |
| 7 | [Capacity engine](phase-07-capacity-engine.md) | L | 5 |
| 8 | [Booking lifecycle: hold, confirm, extend, cancel, expire](phase-08-booking-lifecycle.md) | L | 6, 7 |
| 9 | [Multi-resource claims and any-available assignment](phase-09-multi-resource-and-any-available.md) | L | 8 |
| 10 | [Reschedule, series, cancellation policy and no-show](phase-10-reschedule-series-policy-no-show.md) | L | 9 |
| 11 | [Signed webhooks](phase-11-webhooks.md) | M | 8 |
| 12 | [Waitlist](phase-12-waitlist.md) | L | 8, 11 |
| 13 | [Public customer gate](phase-13-public-gate.md) | L | 8, 12 |
| 14 | [Waiting room: ordered admission queue](phase-14-waiting-room.md) | L | 13 |
| 15 | [Flash-sale load and invariant checks](phase-15-flash-sale-load-and-invariants.md) | L | 14 |
| 16 | [Ops, end-to-end smoke and docs](phase-16-ops-smoke-and-docs.md) | M | 10, 15 |

Phases 6 and 7 can run in parallel (disjoint contexts); phase 11 can start once phase 8's events exist. Everything else is
sequential.

## Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Full RRULE (BYSETPOS, EXDATE/RDATE, nth weekday) is the longest tail of edge cases and DST bugs | High | High | Spike picks the library by vectors first; vectors live in `contracts/` and run in the library test and through the API; no hand-written expansion |
| The public gate adds an attack surface and doubles load-test scope | Med | High | Reuse survey's guard pattern; it is the cheapest piece to defer to a later plan if the schedule slips, because it only calls existing use cases |
| Confirm arrives after the hold lapsed and the Tenant has already charged | Med | High | `extend` before charging, grace window, a typed `409 hold-lapsed` so the Tenant can refund through Billing, documented recipe in `docs/booking.md` |
| Exclusion constraint cannot see expiry, so a stale hold blocks a range | Med | Med | Claim removes the resource's expired holds in the same transaction; test with a frozen clock |
| Multi-resource claims deadlock | Med | Med | Fixed lock order; PostgreSQL deadlock detection plus bounded retry; test with crossing resource sets |
| Hot capacity pool still queues under flash load | Med | Med | One row per place and `SKIP LOCKED`; counter baseline measured beside it; connection pool is the real limit, so admission control caps in-flight holds per session |
| Per-instance rate limit and admission control multiply with instances | Med | Low | Documented as in survey; the limit blunts scripts, it does not account |
| The ordered queue adds a second moving part on the hottest path and polling traffic still reaches the web tier | Med | Med | Insert-only tickets, one watermark row written by one worker, cached per instance; `queue_mode = NONE` falls back to admission control with no migration; the load test measures both |
| `btree_gist` not creatable by the owner role | Low | High | Phase 3 proves it on the compose database before any engine code |
| Shared ops, compose and contracts files are edited by in-flight plans | High | Low | Append-only edits, re-read before each edit, small commits |
| Scope is the largest since Billing | High | Med | Sixteen small phases with their own gates; each engine is proven by tests before the lifecycle sits on it |

## Validation log

Session 2026-11-16 (planning validation, four questions):

1. **Waiting room.** Question: per-instance admission control (recommended) or an ordered queue stored in Postgres. Answer: **ordered
   queue in Postgres.** This reverses the advisor's recommendation (the advice said no global queue); the user's choice stands. It
   added phase 14, moved the load test and ops to phases 15 and 16, and removed the "no ordered waiting room" non-goal. Admission
   control stays as the inner cap.
2. **Waitlist versus direct holds.** Answer: a direct hold may take a freed place; the waitlist guarantees order among waiters only.
   Recorded in Key decisions and phase 12's risk.
3. **Default timings.** Answer: keep hold 10 minutes, extension cap 30 minutes, grace 60 seconds, offer window 10 minutes, token cap
   1 hour, 120 requests per minute per fingerprint.
4. **Series default.** Answer: all-or-nothing, at most 52 occurrences; `SKIP_CONFLICTS` is chosen per request.

Not asked, decided by the plan and open to the user's correction: the load targets (p95 ≤ 150 ms, p99 ≤ 400 ms at 500 requests per
second; calibrated in phase 15), the queue defaults (50 admissions per second, 120-second admission window) and the ticket token
reusing the customer-token keys.

## Unresolved questions

- Which Java RRULE library passes the vectors (decided by the phase 5 spike).
- How crm builds its webhook header set (phase 1 confirms; this plan assumes an app-local set).
