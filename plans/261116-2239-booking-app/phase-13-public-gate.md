---
phase: 13
title: "Public customer gate"
status: pending
priority: P1
effort: "L"
dependencies: [8, 12]
---

# Phase 13: Public Customer Gate

## Goal

A Tenant's customer's browser can see availability, hold, release its own hold and join the waitlist directly against booking with a
short-lived customer token, protected by anti-bot limits, and can never confirm.

## Context

This copies survey's public pattern, in a separate build: an HMAC-signed, key-rotated opaque token (not a JWT), a guard filter that
runs before authentication (rate and body limits), a per-instance token bucket keyed by an HMAC of the client IP with a daily-rotated
secret (`ClientFingerprint`; `core/core-visitor` exists if it offers the same), a honeypot, and a permit-all matcher for `/p/**` and
`/booking/p/**`. Tenant comes from the host. The limit is per instance and documented as N times the setting. Admission control is the
inner cap: a bounded number of in-flight holds per session or offering per instance, `429 Retry-After` with jitter. The ordered waiting
room that sits in front of it is phase 14. CORS is new (survey has none): allowed origins are registered by the Tenant.

## Files to create

- Migration `V12__gate.sql`: `gate_settings(tenant_id, allowed_origins, max_token_ttl_seconds)`; regenerate jOOQ
- `booking/contexts/gate/src/{domain,application,api,adapter}/...`: `CustomerTokens` (sign, verify, keys), `PublicGuardFilter`,
  `PublicRateLimiter`, `Honeypot`, `AdmissionControl`, `CorsPolicy`, `PublicController`, token issuing controller
- `booking/apps/app-boot/.../config/GateConfiguration.java`, key settings `BOOKING_TOKEN_KEYS` with an active key id
- Tests: `CustomerTokenTest`, `PublicGuardTest`, `PublicApiIntegrationTest`, `AdmissionControlTest`, `CorsPolicyTest`, `GateIsolationTest`

## Tasks

1. **Token issue.** `POST /customer-tokens` (Tenant System, `booking:token:issue`) with `customerRef`, `scopes` from `browse`, `hold`,
   `waitlist`, an optional `offeringId`, and `ttlSeconds` up to the cap. The token binds tenant, customerRef, scopes, offering and
   expiry, and carries the signing key id; rotation keeps previous keys for verification.
2. **Public surface** under `/p/`: `GET /p/offerings/{id}` and `/availability` (scope `browse`; `remaining` only if the offering shows
   it), `POST /p/bookings` and `DELETE /p/bookings/{id}` (scope `hold`; a booking with source `GATE`, only the token's own
   `customerRef`, only while `HELD`), `POST /p/waitlist`, `DELETE /p/waitlist/{id}`, `GET /p/waitlist/{id}` (scope `waitlist`).
   There is no confirm, extend, reschedule or reads of other customers' data.
3. **Guard order.** Rate limit and body limit first, then the honeypot field check, then token verification, then the tenant from
   the host; a token for another tenant is refused with the same code as a missing one.
4. **Admission control.** A map keyed by `(tenant, session or offering)` with a bounded semaphore; when full, `429` with
   `Retry-After` between 1 and 3 seconds chosen at random; entries are size-bounded, least recently used dropped.
5. **CORS.** `PUT /settings/gate` (Tenant System, `booking:settings:manage`) sets allowed origins; the filter echoes only a registered
   origin and answers preflight; no wildcard, no credentials.
6. **Hygiene.** No personal data is logged; fingerprints only; headers that could leak internals are removed.
7. **Webhook on public hold.** A gate hold emits `booking.held` with `source = GATE`, so the Tenant can start payment.

## Verification

- A missing, expired, tampered, wrong-tenant, wrong-scope or other-customer token is `401`/`403`, and the same token shape for another
  tenant's host is refused.
- The gate has no route that confirms; a confirm call with a customer token is `403` or `404`.
- The 121st request in a minute from one fingerprint is `429` with `Retry-After`; a filled honeypot and an oversize body are refused.
- Admission control caps in-flight holds per key and releases the permit on failure.
- CORS answers only registered origins; an unregistered origin gets no allow header.
- 200 parallel gate holds on a 20-place session create exactly 20 holds.

## Risks

- A new public attack surface. Mitigated by reusing survey's proven filter ordering and limits, and by keeping the gate a thin
  layer over the existing use cases; if the schedule slips this is the one piece that can move to its own plan without touching the
  lifecycle.
