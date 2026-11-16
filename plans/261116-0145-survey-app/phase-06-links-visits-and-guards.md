---
phase: 6
title: "Links, visits and public guards"
status: done
priority: P1
effort: "M"
dependencies: [4]
---

# Phase 6: Links, Visits and Public Guards

## Goal

A Tenant issues public and personal links; opening one creates a visit that records where it came from; the public
surface is protected by signed tokens, rate limits and size limits.

## Files to create / modify

- Create: `survey/contexts/collection/...` (`link`, `visit`, `guard` packages)
- Create: migration `V3__links_and_visits.sql`: `survey_links` (id, tenant_id, survey_id, kind `PUBLIC|PERSONAL`,
  source, external_ref, expires_at, status `ACTIVE|REVOKED`, created_at; unique `(survey_id, source)` where kind =
  `PUBLIC`; index `(survey_id, external_ref)`), `visits` (id, tenant_id, survey_id, link_id, token_hash, source,
  utm jsonb, referrer_domain, device_class, browser_family, country, state `OPENED|STARTED|COMPLETED|ABANDONED`,
  response_id (nullable, set at the first draft save), opened_at, started_at, last_activity_at, completed_at),
  `visit_page_events` (visit_id, page_id, viewed_at)
- Modify: `survey/apps/app-boot` config for `SURVEY_LINK_KEY_ID`, `SURVEY_LINK_SECRET`, `SURVEY_PUBLIC_BASE_URL`,
  `SURVEY_GEOIP_PATH`
- Modify: `contracts/openapi/survey.yaml`

## API

| Method and path | Permission | Notes |
|---|---|---|
| `POST /surveys/{id}/links` | `survey:link:manage` | A public link for a source; `409` if that source exists |
| `POST /surveys/{id}/links/personal` | `survey:link:manage` | Bulk ≤ 1,000 `{external_ref, source, expires_at}`; returns URLs |
| `GET /surveys/{id}/links`, `GET /links/{id}` | `survey:link:manage` | URL readable at any time |
| `POST /links/{id}/revoke` | `survey:link:manage` | |
| `POST /p/l/{token}/open` | public | Creates a visit; returns `{visit_token, version, locale}` |
| `GET /p/v/{visit_token}/schema?locale=` | public | The version for one locale, without scores |
| `POST /p/v/{visit_token}/pages/{page_id}/view` | public | Page event; ≤ 200 per visit |

## Tasks

1. **Tokens.** A link token is `base64url(link_id ‖ key_id ‖ HMAC-SHA256(secret, link_id ‖ key_id))`, reusing the
   approach of mailroom's tracking tokens. Every invalid, revoked, expired or foreign-tenant token answers the same
   `404` body.
2. **Open.** Resolve the link, check the survey is `OPEN` and inside its window, create a visit with a random 32-byte
   token (store only SHA-256), copy the link's source, keep `utm_source|medium|campaign|term|content` (each ≤ 100
   characters), the referrer's registrable domain only, the device class and browser family from a small ordered rule
   set (bots marked `bot` and excluded from the funnel), and the country from the GeoIP reader. A personal link that
   already has a completed response returns `409 already_completed`; one with an open visit returns that visit.
3. **GeoIP.** Read the DB-IP Lite country database (`.mmdb`, CC BY 4.0, attribution in `docs/survey.md`) at
   `SURVEY_GEOIP_PATH` with `com.maxmind.db:maxmind-db`; a missing file
   leaves `country` null and the health detail says so; the file's build date is in the health detail.
4. **Rate limits.** Token buckets keyed by an HMAC of the client IP with a daily-rotated key and by survey id, held in
   memory per instance in a size-bounded map (with N instances the effective limit is N times the setting); `429` with `Retry-After`. Body ≤ 256 KiB on every public endpoint.
5. **Honeypot.** The schema response names one decoy field per visit; a submit (phase 7) that fills it is accepted with
   `202` and discarded, so a bot learns nothing.
6. No IP, user agent string, token or referrer path is logged.

## Verification

- `cd survey && ./gradlew test --tests '*collection*'` green.
- Tests: tampered, revoked, expired and other-tenant tokens give identical `404` bodies; bulk personal links return
  URLs that open; the referrer is reduced to its domain; a request from a bot UA creates a `bot` visit excluded from
  counts; the 201st page event is refused; the bucket returns `429`; a missing GeoIP file gives `country = null` and a
  health detail.

## Risks

- UTM values are attacker-controlled; they are stored bounded and only ever shown as data, never used as keys.
