---
phase: 4
title: "Campaigns, links, QR and bulk links"
status: done
priority: P1
effort: "L"
dependencies: [3]
---

# Phase 4: Campaigns, Links, QR and Bulk Links

## Goal

A Tenant creates campaigns and tracked links with UTM, custom slug, QR, expiry, click cap, password and several
destinations with rules, and issues personal links in bulk, all through the API and all validated at save time.

## Files to create / modify

- Create: `attribution/contexts/links/...` (`campaign`, `link`, `destination`, `qr` packages)
- Create: migration `V2__campaigns_and_links.sql`: `campaigns` (id, tenant_id, name, slug, channel, status
  `ACTIVE|ARCHIVED`, attribution_model `FIRST|LAST|LINEAR` default `LAST`, window_days, currency, starts_on, ends_on,
  version), `links` (id, tenant_id, campaign_id nullable, slug, kind `STANDARD|PERSONAL`, external_ref, status
  `ACTIVE|PAUSED|ARCHIVED`, expires_at, max_clicks, password_hash, utm jsonb, version; unique `(tenant_id, slug)`; index
  `(tenant_id, external_ref)`), `link_destinations` (id, link_id, position, url, weight, match jsonb `{device:[],country:[]}`,
  is_fallback), `link_counters` (link_id, clicks_counted)
- Modify: `contracts/openapi/attribution.yaml`; `libs/jooq-schema` (regenerate)

## API

| Method and path | Permission | Notes |
|---|---|---|
| `POST/GET /campaigns`, `GET/PATCH /campaigns/{id}`, `POST /campaigns/{id}/archive` | `campaign:manage` / `campaign:read` | ETag and `If-Match`; model or window change is recorded here and acted on in phase 8 |
| `POST/GET /links`, `GET/PATCH /links/{id}`, `POST /links/{id}/pause`, `/resume`, `/archive` | `link:manage` / `link:read` | ETag and `If-Match`; `GET /links?campaign_id=&external_ref=&status=` |
| `POST /links/bulk` | `link:manage` | JSON or `text/csv`, ≤ 1,000 rows, `Idempotency-Key` required |
| `GET /links/{id}/qr?format=png\|svg&size=` | `link:read` | Encodes `https://<tenant-host>/go/<slug>?e=qr` |

## Tasks

1. **Slug.** 3-64 characters `[a-z0-9][a-z0-9_-]*`, lower-cased, unique per Tenant; generated slugs are 10 characters of
   unambiguous base32. `409 slug_taken` on conflict.
2. **Destination validation at save.** URL ≤ 2,048 characters, scheme `http` or `https` only, no userinfo, host not
   `localhost`, not a private or link-local IP literal, not the suite's base domain or any Tenant host. The destination's
   query may not already contain the Tenant's click parameter (`409 click_param_conflict`). No DNS lookup and no fetch.
3. **Destinations and rules.** One to ten destinations per link; exactly one `is_fallback`. A destination's `match` lists
   device classes and country codes; an empty `match` always passes. Weights are positive integers.
4. **UTM.** The link holds `{source, medium, campaign, content, term}` (≤ 100 characters each); `utm_campaign` defaults
   to the campaign's slug. The redirect (phase 5) merges them over any `utm_*` already on the destination; the link's
   values win. Documented.
5. **Cap, expiry, password.** `max_clicks` ≥ 1, `expires_at` in the future at save, `password` accepted on write only and
   stored as a BCrypt hash (Spring Security's encoder, already on the classpath). Never returned.
6. **QR.** ZXing core, PNG and SVG, size 128-1024, error correction `M`; no storage, generated on request.
7. **Bulk links.** Each row `{external_ref, entry?}` plus shared `{campaign_id, destination, utm, expires_at}`; the
   response lists `{id, slug, url, external_ref}`. A repeated `Idempotency-Key` with the same body returns the same
   result; with a different body `422`. `external_ref` is bounded (≤ 128 characters) and indexed.
8. **Public URL.** `url = https://<tenant primary domain>/go/<slug>` from the replica's tenant domains; the `e` entry
   parameter is `[a-z0-9_-]{1,32}`.
9. **Currency.** A campaign carries one ISO currency; changing it after spend or conversions exist is refused (`409`).

## Verification

- `cd attribution && ./gradlew test --tests '*links*'` green.
- Tests: slug conflict; every rejected destination class (scheme, userinfo, private IP, suite host, click-parameter
  conflict); exactly-one-fallback; a stale `If-Match` returns `412` with the current ETag; bulk of 1,000 returns 1,000
  distinct working slugs and the same idempotency key returns the same list; the QR decodes back to the expected URL;
  password hash is never in any response or log; tenant A cannot read tenant B's campaign or link.

## Risks

- Destination checks are the only guard against turning the suite's domain into a phishing relay. Keep the test list of
  rejected hosts in one parameterized test so a new case is a data row.
- BCrypt on a public unlock path is slow by design: phase 5 must rate-limit it before it runs.
