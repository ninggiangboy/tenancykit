---
phase: 5
title: "Redirect path, rules and click publishing"
status: pending
priority: P1
effort: "L"
dependencies: [2, 4]
---

# Phase 5: Redirect Path, Rules and Click Publishing

## Goal

`GET /go/{slug}` resolves a link from memory, applies expiry, cap, password and destination rules, appends the signed click
id, answers `302`, and hands a click event to a bounded buffer that publishes to the broker, all without a database write
and without ever failing because analytics fails.

## Files to create / modify

- Create: `attribution/contexts/links/.../redirect/` (`LinkResolver`, `DestinationSelector`, `ClickTokens`,
  `LinkCache`), `attribution/contexts/tracking/.../ingest/` (`ClickEvent`, `IngestBuffer`, `IngestPublisher`)
- Create: `attribution/apps/app-boot/.../web/RedirectController` (public, `@Profile("api")`)
- Modify: messaging configuration (exchange `attribution.ingest`, queue `attribution.ingest` with the suite DLQ),
  `contracts/attribution/ingest/click.schema.json`, `contracts/openapi/attribution.yaml`
- Add dependency: `com.github.ben-manes.caffeine:caffeine` (version managed by the Spring Boot BOM)

## Flow

```text
GET /go/{slug}[?e=entry]
  host -> tenant (404 if no entitlement)        cache(tenant, slug) -> rules | negative entry (30 s)
  status/expiry check                           cap? one conditional counter update
  password? 401 JSON until POST /go/{slug}      select destination (filter by match, weighted random, else fallback)
  click_id = random 128-bit; token = signed     Location = destination + utm merge + aid=<token>
  buffer.offer(ClickEvent)                      302, Cache-Control: no-store
```

## Tasks

1. **Cache.** A Caffeine loading cache keyed `(tenant, slug)` with a 30 s TTL, single-flight loads (a viral link's cold
   start issues one query), bounded size, and negative entries for unknown slugs so random slugs cannot hammer the
   database. A link change takes at most the TTL to reach other instances; document it.
2. **Outcomes.** Unknown, archived, paused or entitlement-less: `404` with one constant body. Expired or cap reached: `410`.
   Password required: `401` JSON `{"error":"password_required"}`; `POST /go/{slug}` with `{"password":...}` answers `302`
   on success and `403` otherwise. Failures are limited to 5 per link and IP hash per 10 minutes, checked before BCrypt.
3. **Cap.** Only links with `max_clicks` do `update link_counters set clicks_counted = clicks_counted + 1 where link_id = ?
   and clicks_counted < ?`; zero rows means `410`. Others touch no database.
4. **Destination.** Filter by `match` (device and country from `core-visitor`), weighted random among the survivors, else
   the fallback. The chosen destination id goes on the click.
5. **Click token.** `base64url(click_id ‖ key_id ‖ HMAC-SHA256(secret, click_id ‖ key_id))`; verification is a pure function
   used by phases 7 and 8. Appended to the destination under the Tenant's parameter name; the fragment is preserved.
6. **Event.** `ClickEvent` carries event id, tenant, click id, link, destination, campaign, time, device class, browser
   family, country, referrer domain, entry, traffic class and the daily visitor hash. It carries no IP and no user agent.
   `BOT` comes from the classifier; `SUSPECT` from an in-memory counter (more than 30 clicks per visitor hash and link per
   minute). Bots and link-preview fetchers still receive the `302`, so previews work, and are stored as `BOT`.
7. **Buffer and publisher.** A bounded in-memory queue (10,000 events). A single flusher thread publishes batches (500
   events or 200 ms) to `attribution.ingest` with publisher confirms. If the broker is unavailable the buffer fills and the
   oldest events are dropped and counted (`attribution.ingest.dropped`); a gauge exposes buffer depth. The controller
   never waits on the publisher.
8. **Public limits.** In-memory token buckets keyed by the visitor hash (600 per minute) and per link; a refusal is `429`
   with `Retry-After`. With N instances the effective limit is N times the setting; documented. `HEAD` is answered like
   `GET` without recording a click.
9. **Logging.** No IP, user agent, token, destination query or password is logged.

## Verification

- `cd attribution && ./gradlew test --tests '*redirect*' --tests '*ingest*'` green.
- Tests: every outcome above; a cap of 5 with 100 concurrent requests yields exactly 5 `302` and 95 `410` and a counter of 5;
  weighted destinations over 10,000 clicks land within 3% of their weights; a tampered token fails verification; a bot UA
  still gets `302` and `traffic_class = BOT`; with the broker stopped (Testcontainers) redirects still answer and
  `dropped` rises after the buffer fills; with the broker running, 2,000 clicks produce 2,000 messages on the queue.
- A smaller load run (JDK `HttpClient`, 200 rps for 20 s on a laptop) reports p95 under the target in `docs/attribution.md`,
  or the measurement is recorded there with a reason.

## Risks

- A capped link is a hot row: exactness is paid for with throughput on that link only. Documented in the API description.
- The 30 s cache means a paused or archived link keeps redirecting for up to 30 s on other instances.
- Safari may strip tracking parameters in some modes; the click id then does not reach the page and only the postback with
  a stored click id can attribute (phase 7 documents the fallback).
