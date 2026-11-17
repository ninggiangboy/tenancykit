# Booking

The suite's app for letting a Tenant **sell time or places**: an appointment with a staff member and a room, a rental,
a class or a session with N seats, a table for four, a recurring weekly slot. It is a separate deployable
(`booking/`, root package `dev.ngb.booking`) with the three runtimes every app has: `migrate`, `api` and `worker`.

Booking never touches money. It holds a place, tells the Tenant the hold exists, and the Tenant charges through
[Billing](invoicing-and-subscriptions.md) and then confirms. Nothing in this app stores a price, a fee or a currency.

## What it is, and what it is not

| It is | It is not |
|---|---|
| The owner of what may be booked, when it is open, and who holds what | A payment system: no price, no charge, no refund. Billing owns money |
| One `Booking` lifecycle over two inventory engines, capacity and interval | A seat map or a ticket: no named seats, no QR code, no check-in |
| A producer of signed `Booking-*` webhooks to the Tenant's own endpoints | A consumer of another app's business data, and a caller of another app: it keeps the Platform replica and calls nothing |
| An API with a thin public gate for the Tenant's own customers | A UI. It has no page, and the gate exists only so a Tenant's own site can call it |
| A store of a Tenant's customers as an opaque `customerRef` | A Customer: it stores no name, phone, email or any other personal data |

A Tenant's customer is an opaque reference the Tenant chooses. Booking never resolves it, matches it, or reads it back
other than as the string it was given.

## Runtimes

| Profile | Runs | Migrates |
|---|---|---|
| `api` | HTTP: the catalog, availability, bookings, series, waitlist, webhooks, customer tokens and the public gate | no |
| `worker` | The `platform.events` listener, the outbox relay, webhook delivery, the replica rebuild, hold expiry, session materialization, waitlist offers, queue admission and retention | no |
| `migrate` | Flyway as the table owner, then exits | yes |
| default (no profile) | `api` and `worker` together, for dev and tests | yes |

The worker owns every scheduled job, as in every app: `TenantSweep` runs each job per tenant under row-level security
with bounded virtual threads.

## Contexts

| Context | Owns |
|---|---|
| `platform` | A replica of the Platform data booking enforces: `tenants`, `tenant_domains`, `tenant_systems`, `user_grants`, `app_entitlements`, plus the read ports `TenantDirectory` and `UserDirectory`. Nothing else reaches these tables |
| `catalog` | Resources, weekly hours, calendar exceptions, groups, offerings, requirements, cancellation windows, blocks, session series, sessions and pools, and the recurrence port |
| `inventory` | The two engines behind one port: interval claims enforced by an exclusion constraint, and capacity places claimed with `FOR UPDATE SKIP LOCKED`; also availability and resource assignment |
| `booking` | The `Booking` aggregate and its state machine, hold, confirm, extend, cancel, expire, reschedule, series, cancellation policy, no-show and the waitlist |
| `gate` | Customer tokens, the public guard (rate limit, body limit, honeypot), admission control, CORS and the ordered waiting room |
| `webhook` | Webhook endpoints, signing keys, deliveries and the event-type catalog |

Allowed edges are one-way: `catalog` ← `inventory` ← `booking` ← `gate`, and `webhook` reads booking events only
through the outbox. Every cross-context call goes through the other context's `api` package, and ArchUnit fails the
build otherwise.

## The two engines, one port

`InventoryEngine` has two implementations and no registry. The port states which promise each makes.

**Capacity.** One `capacity_units` row per place. A claim picks `qty` rows with
`SELECT ... FOR UPDATE SKIP LOCKED` and a conditional `UPDATE`; concurrent callers take different rows instead of
queueing on one counter. There is no counter column to drift, so the number of places a Tenant sees is exactly the
number of free rows. This is the flash-sale engine: it promises throughput, not fairness — the ordering a sale needs
comes from the waiting room in front of the gate.

**Interval.** One `interval_claims` row per resource and claimed range, enforced by PostgreSQL:

```sql
EXCLUDE USING gist (tenant_id WITH =, resource_id WITH =, during WITH &&)
```

Ranges are half-open, so back-to-back bookings are legal. The constraint cannot see `expires_at`, so a claim first
deletes the same resource's lapsed holds in the same transaction and then inserts. A conflict maps SQLSTATE `23P01`
to `409 slot-taken`.

Both engines are keyed by `booking_id`, and a confirmed claim has no expiry.

## The lifecycle

```
HELD ──confirm──▶ CONFIRMED ──cancel──▶ CANCELLED
 │                   └────no-show─────▶ NO_SHOW
 ├──cancel──▶ CANCELLED
 └──expire──▶ EXPIRED
```

`POST /bookings` creates the booking in `HELD`; "hold" is this state, not a second resource. Every transition is a
conditional update (`WHERE status = :from`) so confirm and expiry race to exactly one winner.

- **Confirm after payment.** Confirm is accepted while the booking is `HELD` and its claims are still held, even a
  little past `holdExpiresAt` (the grace window, reclaimed only by the worker). Once the worker has expired it, the
  answer is `409 hold-lapsed`. A Tenant that charged for a lapsed hold refunds through Billing; booking reports the
  fact and holds no money.
- **Extend.** `POST /bookings/{id}/extend` lengthens a hold once, up to the cap in settings, before the Tenant charges.
- **Expiry.** `ExpireHoldsJob` picks `HELD` bookings with `holdExpiresAt + grace < now` using
  `FOR UPDATE SKIP LOCKED`, moves them to `EXPIRED`, releases their claims and appends `booking.expired`.
- **Reschedule.** Claims the new place and releases the old one in one transaction, keeping the booking id and its
  status. If the new place is taken, the booking and its claims are unchanged.
- **Series.** A repeat booking is one hold per occurrence. `ALL_OR_NOTHING` (the default) claims every occurrence or
  none; `SKIP_CONFLICTS` books what it can and reports the rest. At most 52 occurrences.
- **Cancellation policy.** A cancellation of a `CONFIRMED` booking records the Tenant's `policyCode` for the window it
  fell in (`NONE` with no windows or for a held booking, `AFTER_START` after the start). No amount is stored.
- **No-show.** Allowed on a `CONFIRMED` booking once its start has passed. The claims stay as history.

## Lock order

Booking row first, then resources by ascending id, then units by ascending id. Reschedule inserts the new claims
before releasing the old ones in the same transaction, and the interval engine removes a booking's own old range
before inserting its new one so the exclusion constraint never sees both. This follows
[concurrency-and-locking.md](concurrency-and-locking.md); PostgreSQL's deadlock detector plus a bounded retry is the
safety net, and crossing resource sets are tested.

## Time and recurrence

Instants are stored as UTC `timestamptz`; each resource carries its own IANA zone, and recurrence and open hours are
resolved in that zone, never in the caller's.

- A nonexistent local time (inside a spring-forward gap) moves forward by the gap; an ambiguous one (inside a
  fall-back overlap) takes the first occurrence, as RFC 5545 says.
- Recurrence is expanded by a library behind one port, `RecurrenceExpander(rule, zone, window) -> instants`; nothing
  else in booking names the library. The chosen library is the one that passes every vector in
  [contracts/booking/recurrence-vectors](../contracts/booking/recurrence-vectors/); the vectors are computed from the
  tz database and the RFC text, never copied from the library.
- Availability is computed at query time from weekly hours, exceptions, blocks and existing claims. It is never
  materialized.
- Capacity sessions are the exception: a recurring session is materialized into rows inside a rolling horizon
  (default 120 days) on create and daily by the worker, idempotently by `(series_id, starts_at)`.

## Recurrence

The expander behind `RecurrenceExpander` is `Rfc5545RecurrenceExpander` in
`booking/contexts/catalog/.../adapter/recurrence/rfc5545/`, and it is the only class that names the library. The spike's
two candidates were `org.dmfs:lib-recur` and `org.mnode.ical4j:ical4j`; measured against every vector, `lib-recur`
(0.17.1) passes all of them, so it is the one dependency. The port is what a later swap changes, and nothing above it
moves.

`lib-recur` owns the `RRULE` grammar — frequency, interval, `COUNT`/`UNTIL`, `BYDAY` (with ordinals), `BYMONTHDAY`,
`BYMONTH`, `BYSETPOS` and `WKST` — and rejects the parts this service does not expand (`BYYEARDAY`, `BYWEEKNO`,
`BYHOUR`, `BYMINUTE`, `BYSECOND`) rather than reading them as if they said nothing. `EXDATE` and `RDATE` lines are
applied around the rule's own instants, with `RDATE` kept even when it sits outside the rule's bound. Every occurrence
is resolved by turning the intended local wall-clock start into an instant in the caller's zone, which is what keeps a
spring-forward start at its local hour and takes an ambiguous one at its first occurrence.

All twelve vectors under [contracts/booking/recurrence-vectors](../contracts/booking/recurrence-vectors/) pass through
it, both in the expansion test and through `POST /api/v1/recurrence/preview`.

## Availability

`GET /offerings/{id}/availability?from&to&resource=` returns start instants on the offering's `slot_step` grid:
inside open hours (weekly hours, replaced by an `OPEN` exception, cut by `CLOSED` exceptions and blocks), with
`[start, start + duration)` inside open time (buffers may overhang), at or after `now + leadTime`, within the
horizon, and with the buffered range free of claims. For several requirements, a start is free only if every
requirement has a free, distinct resource. Results are keyset-paged by start instant.

## Groups and assignment

A requirement names one resource or one group. The group's strategy picks the member:

| Strategy | Picks |
|---|---|
| `PRIORITY` | The candidate with the lowest priority number |
| `ROUND_ROBIN` | The next member after the last one assigned, advancing the group's cursor in the booking transaction |
| `LEAST_BUSY` | The member with the fewest booked minutes on the local day of the start, ties by priority |
| `FIT_SEATS` | The single member with the fewest seats that still fits the party, or the smallest-waste combination of up to `max_combine` members |

Candidates are ordered by the strategy; one that is outside open hours, blocked or already claimed is skipped, and a
complete assignment never uses the same resource for two requirements. If no complete distinct assignment fits, nothing
is claimed and the answer is `slot-taken`. Claims are inserted in ascending resource id whatever order the requirements
name, so two bookings of crossing requirement sets meet the same row first instead of deadlocking.

## Reschedule, series and no-show

A reschedule keeps the booking id and status and moves the place in one transaction: the old claims are released and
the new ones taken, so a refusal rolls the release back and the booking stays where it was. Shifting a range by minutes
inside its own old range works because the exclusion constraint never sees both ranges at once. A hold that is
extended, cancelled, expired or rescheduled writes its wake-up for the waitlist in the same transaction.

A series stores the Tenant's RRULE lines and one `HELD` booking per occurrence, all sharing a hold expiry. The rule is
expanded from the local `dtstart` in its zone, so a weekly series keeps the same wall-clock time across a DST change.
`ALL_OR_NOTHING` books every occurrence in one transaction and answers `409 series-conflict` with each failing
occurrence; `SKIP_CONFLICTS` books the free ones and reports the rest. A series holds at most 52 occurrences.

A cancellation of a confirmed booking records the offering's first cancellation window whose threshold the remaining
notice still meets, as its `policy_code`: `NONE` when no window matches, `AFTER_START` once the start has passed, and
always `NONE` for a hold. A confirmed booking whose start has passed can be marked a no-show; the claims stay as
history and `booking.no-show` carries `policyCode` `NO_SHOW`.

## Waitlist and waiting room

A held place that frees by cancel, expiry or reschedule writes a wake-up row in the releasing transaction; the worker
processes wake-ups outside it, and also scans waiting targets on each poll so a place freed without a wake-up (a pool
resize) still reaches the line. The next `WAITING` entry that fits receives the place as an ordinary `HELD` booking with
source `WAITLIST` and the offer window as its TTL; the entry follows that booking's fate: confirming accepts it, while
its expiry or cancellation lapses it, appends `waitlist.lapsed` and wakes the target again. An entry that does not fit
keeps its position while a later one is offered. Joining is refused for a target that is free (`422 TARGET_AVAILABLE`);
one customer has one `WAITING` entry per target (`409 ALREADY_WAITING`), and a target's line is capped by
`waitlistCapPerTarget` (`409 waitlist-full`). The waitlist guarantees order *among waiters* only: a direct hold may take
a freed place first, and this is documented rather than hidden.

For a rush sale, an offering can set `queueMode = REQUIRED`. A customer enters the gate's queue, receives an
insert-only numbered ticket and a signed ticket token, and is admitted in ticket order at the offering's
`admitPerSecond`. Admission advances one watermark row per target, written only by the worker; a poll reads an
in-memory copy of that watermark refreshed at most once per second, never a ticket row. A hold needs an admitted,
unexpired ticket of its own, and one ticket funds one hold. `queueMode = NONE` falls back to per-instance admission
control alone.

## The public gate

The gate is a thin layer over the same use cases, in booking's own build:

- **Tokens.** An HMAC-signed, opaque customer token with a key id and a short lifetime, bound to tenant, `customerRef`,
  a scope set (`browse`, `hold`, `waitlist`) and optionally one offering. The Tenant System mints it with
  `POST /customer-tokens`; keys rotate, and previous keys stay valid for verification.
- **Guard order.** Rate limit and body limit first, then the honeypot, then token verification, then the tenant from
  the host. A token for another tenant is refused with the same code as a missing one.
- **Admission control.** A bounded number of in-flight holds per session or offering per instance, answering
  `429 Retry-After` with jitter. The limit and the admission cap multiply with instances, exactly as survey's
  documented rate limit does; they blunt a script, they do not account.
- **CORS.** Only the origins the Tenant registered; no wildcard, no credentials.
- **What the gate can do.** Browse an offering and its availability, create and release its own `HELD` booking, join
  and leave the waitlist and read its position. It has no route that confirms, extends, reschedules or reads another
  customer's data.

The guard reads the token but never lets Spring Security's JWT decoder see it: the resource-server bearer resolver
returns nothing on `/p/**`, so a customer token cannot be mistaken for a malformed suite JWT. Keys come from
`booking.gate.keys` with `booking.gate.active-key-id`; a blank key fails startup. The rate bucket is keyed by the daily
HMAC of tenant, address and user agent, so no address is stored or logged.

A rush offering sets `queueMode = REQUIRED`. `POST /p/queue` inserts a ticket numbered from one shared sequence, returns
a signed ticket token, and the admission job advances the target's watermark in ticket order at `admitPerSecond`.
`GET /p/queue/status` answers from the instance's cached watermark (refreshed at most once a second), never from a
ticket row. A hold on a queue-required offering must carry `X-Queue-Ticket`: the ticket must be admitted and inside
`admitWindowSeconds`, and one ticket funds exactly one hold. A failed hold puts the ticket back so the customer may
retry until the window ends.

## Webhooks

Every fact a Tenant may act on leaves as a signed `Booking-*` webhook to the Tenant's registered endpoints, once per
endpoint, with retries. The headers are `Booking-Event-Id`, `Booking-Event-Type`, `Booking-Timestamp`,
`Booking-Key-Id`, `Booking-Signature` (the `v1=` HMAC-SHA256 of `timestamp + "." + rawBody`) and `Booking-Attempt`.
Booking declares no AMQP exchange of its own, so no app binds to it and it binds to nothing but `platform.events`.

Event types, with a schema each under [contracts/events/booking](../contracts/events/booking/): `booking.held`,
`booking.confirmed`, `booking.rescheduled`, `booking.cancelled`, `booking.expired`, `booking.no-show`,
`waitlist.offered`, `waitlist.lapsed`, `series.created`, `series.cancelled`.

The outbox row and the delivery rows are written in the transaction that records the fact, so a use case that fails
after appending leaves neither. The worker's relay hands each due outbox row to the webhook listener in process, and one
delivery is queued per subscribed endpoint (the unique `(endpoint_id, event_id)` index makes a redelivered row
idempotent). The sender claims due deliveries with `for update skip locked`, posts them outside the transaction, and
records the attempt; a failure retries with backoff and a `500` on one endpoint never affects another. Endpoints are
managed under `booking:webhook:*`; the signing secret is returned only on create and rotation, and a change is
conditional on `If-Match`. A destination must be public https: the destination policy and the DNS guard re-check every
send, and only a deployment that explicitly allows insecure targets (the compose smoke forwarder) may use http or a
private address.

## Settings and defaults

Per tenant, reversible, in settings rather than code:

| Setting | Default | Meaning |
|---|---|---|
| `holdTtlSeconds` | 600 | How long a hold lives before the worker may expire it |
| `maxExtensionSeconds` | 1800 | Total extension a Tenant may add to one hold |
| `graceSeconds` | 60 | How long past the TTL a confirm still succeeds |
| `offerWindowSeconds` | 600 | How long a waitlist offer holds the place |
| `horizonDays` | 120 | How far ahead capacity sessions are materialized |
| `tokenTtlCapSeconds` | 3600 | The longest customer token the Tenant may mint |
| `rateLimitPerMinute` | 120 | The gate's per-fingerprint request limit, per instance |
| `admissionCap` | 8 | In-flight holds per session or offering, per instance |
| `waitlistCapPerTarget` | 1000 | Waiting entries allowed per target |
| `admitPerSecond` | 50 | Queue admission rate for an offering that requires the queue |
| `admitWindowSeconds` | 120 | How long an admitted ticket may be used |

The load targets are p95 ≤ 150 ms and p99 ≤ 400 ms at 500 requests per second against a 5,000-place session on the
compose Postgres. They are thresholds for the load runner, not a promise about another machine: run
`ops/loadtest/booking.sh` with a minted token and the seeded offering, and record the machine beside the numbers. The
runner prints the status histogram and p50/p95/p99 for `/p/bookings`; `ops/smoke/booking-invariants.sh` must pass
after the run.

## Operations

- **Jobs.** Hold expiry, session materialization, waitlist offers, queue admission, queue retention, outbox relay and
  webhook delivery run in the worker only. Each is bounded and idempotent on a rerun: a claim uses `skip locked`, a
  watermark advance uses the highest admitted ticket number, and retention deletes only what is old enough.
- **Keys.** Customer tokens and webhook signing secrets rotate; the active key id is in settings and previous keys
  stay valid for verification. `BOOKING_TOKEN_KEY_ID` and `BOOKING_TOKEN_SECRET` set the customer/queue key;
  `BOOKING_SECRETS_MASTER_KEY` envelops webhook signing secrets.
- **Smoke.** `ops/smoke/booking.sh` plays the Tenant: catalog, gate hold, confirm, waiting room, waitlist offer,
  reschedule, series, invariants. It needs a running compose stack and the usual smoke helpers.
- **Invariant check.** `ops/smoke/booking-invariants.sh` proves no pool is oversold, no unit is owned by a booking that
  is not `HELD` or `CONFIRMED`, no live booking lacks its claims, no interval claim overlaps another on one resource,
  and no held booking is long past `holdExpiresAt + grace`.
- **Isolation.** `ops/smoke/isolation.sh` proves the booking broker user can neither publish to nor bind to another
  app's exchange and that `booking_app` cannot read another database.

## The Tenant recipe

1. `POST /bookings` — a hold; the response carries `holdExpiresAt`.
2. Charge the customer through Billing with the booking id as your own reference.
3. If the charge is slow, `POST /bookings/{id}/extend` once, before the cap.
4. `POST /bookings/{id}/confirm` — `200` and `booking.confirmed`, or `409 hold-lapsed`, in which case refund through
   Billing.

## Non-goals

Stripe or any money, prices or fee amounts; seat maps, named seats and seat selection; tickets, QR codes and check-in;
any UI; external calendar sync; storing personal data of a customer; booking sending any message itself; a booking
AMQP exchange; any call to or from another suite app; third-party CAPTCHA; Redis or any store beyond PostgreSQL and
RabbitMQ; multi-currency; and any code change in an existing app or in `core/`.
