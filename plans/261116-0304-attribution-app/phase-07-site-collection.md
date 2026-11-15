---
phase: 7
title: "Site collection: snippet, page views, sessions, visitor binding"
status: pending
priority: P1
effort: "L"
dependencies: [6]
---

# Phase 7: Site Collection

## Goal

A Tenant adds one script to its site; with consent it records page views and sessions after a tracked click and binds the
visitor to the clicks that brought them, and without consent it records only the landing page view, with no cookie and no
identifier.

## Files to create / modify

- Create: `attribution/contexts/tracking/.../collect/` (`CollectController` in `apps/app-boot/.../web`, `EventValidator`,
  `OriginPolicy`, `VisitorBinder`, `ErasureService`)
- Create: `attribution/apps/app-boot/src/main/resources/static/t/v1/snippet.js` (source in
  `attribution/apps/app-boot/src/main/snippet/`, built by a Gradle task with no runtime dependency)
- Create: migration `V4__site_events.sql`: `page_events` (partitioned by day like `click_events`: event_id, tenant_id,
  click_id, session_id nullable, visitor_id nullable, path, occurred_at, traffic_class), `sessions` (tenant_id, session_id,
  visitor_id, click_id, started_at, last_activity_at, page_count), `visitor_clicks` (tenant_id, visitor_id, click_id,
  bound_at; primary key `(tenant_id, visitor_id, click_id)`)
- Modify: `contracts/attribution/ingest/` (`page-view.schema.json`, `visitor-bound.schema.json`),
  `contracts/openapi/attribution.yaml`, the ingest consumer (new event types), `PartitionJob` (new table)

## API

| Method and path | Auth | Notes |
|---|---|---|
| `GET /t/v1/snippet.js` | public | Static, `Cache-Control: public, max-age=86400`, its SRI hash printed in `docs/attribution.md` |
| `OPTIONS` and `POST /t/v1/events` | public, allowed origin | Batch of at most 20 events, body ≤ 8 KiB |
| `POST /data-subjects/erasure` | `attribution:data:erase` | Body `{visitor_id}` |
| `GET/PUT /settings` | `attribution:settings:manage` | `click_param`, `allowed_origins`, `default_window_days` |

## Tasks

1. **Snippet.** Reads the click parameter from the URL; exposes `attribution.consent(true|false)`, `attribution.page()` and
   (used by phase 8) `attribution.goal(key, {value, currency})`. Without consent it sends one `page_view` carrying the
   click id and nothing else. With consent it stores the click id (cookie, as long as the attribution window) and a random
   visitor id (cookie, 30 days) and a session id (session cookie, 30 minutes of inactivity), and sends events with
   `sendBeacon` falling back to `fetch`. No fingerprinting, no automatic single-page-app route tracking (a manual
   `page()` call), under 4 KB gzipped.
2. **Collect endpoint.** Accepts an event only if its click id passes `ClickTokens` verification, its `Origin` is in the
   Tenant's `allowed_origins`, the body and batch limits hold, and the per-click-id (500 events per day) and per-IP-hash
   (120 per minute, in memory) limits hold. Everything else answers `204` without storing (a probe learns nothing) and is
   counted.
3. **Server-side consent.** The client's `consent` flag is enforced by the server: when false, `visitor_id` and `session_id`
   are dropped before the event is published. The path is stored without query string or fragment.
4. **Binding.** An event with a visitor id and a click id upserts `visitor_clicks`; this is the only link between clicks
   used by the multi-touch chain in phase 8.
5. **Sessions.** Upsert by `(tenant, session_id)`: extend `last_activity_at`, increment `page_count`; a gap over the
   session timeout starts a new session id on the client.
6. **Bots.** The server classifies the request's user agent; bot events are stored as `BOT`.
7. **Erasure.** For a visitor id: delete its page events, sessions and bindings, delete the click rows bound only to it,
   and set `visitor_id`, `click_id` to null on its conversions (phase 8) so the amount stays and the person does not.
   Rollups hold aggregates only and are untouched. The endpoint returns counts of what it removed.
8. **Settings.** `allowed_origins` must be absolute `https` origins (or `http://localhost` for development); wildcard
   origins are refused.

## Verification

- `cd attribution && ./gradlew test --tests '*collect*' --tests '*erasure*'` green.
- Tests: no cookie-setting instruction and no visitor id in any stored row for a `consent=false` run; an event with a bad
  signature, a foreign origin or an oversize body is not stored and still answers `204`; the 501st event for one click id
  in a day is dropped; two clicks bound to one visitor produce two `visitor_clicks` rows; erasure removes exactly that
  visitor's rows and no other visitor's; snippet size under 4 KB gzipped (a test).
- A browser-driven check (Playwright or the repository's existing browser test tool, if any, else a manual script
  recorded in `docs/attribution.md`) loads a page with the snippet, sets consent, navigates two pages and sees the events.

## Risks

- Third-party script blockers and Safari's cookie limits reduce page views and shorten visitor cookies; reports label page
  views as measured by the snippet and the docs say so.
- The consent flag is the Tenant's responsibility; the app can only refuse to store identifiers when told no. Documented
  in the Tenant guide.
