---
phase: 8
title: "Tracking, public endpoints and stats"
status: done
priority: P1
effort: "M"
dependencies: [7]
---

# Phase 8: Tracking, Public Endpoints and Stats

## Goal

Marketing emails carry an open pixel, rewritten links and an unsubscribe link; the public endpoints record what the
recipient does; campaign statistics report it.

## Tracking token

`base64url(version | tenantId | emailId | linkId | kind)` followed by an HMAC-SHA256 tag with a key id for rotation. The
key comes from configuration and is never logged. No expiry (mail is read years later); the token authorises exactly one
action on one email. An invalid token returns the same response as a valid one for pixel and a generic page for the rest,
so nothing is learned from the status code.

## Rewrite step (after rendering)

- Insert the 1×1 pixel before `</body>` (append when absent) — **marketing only**; transactional mail is not tracked
  unless the request opts in.
- Replace each `http(s)` link `href` with `/email/t/c/{token}`, recording `(email_id, position, url)` in `email_links`.
  `mailto:`, `tel:` and the unsubscribe link itself are left alone. The text part keeps original URLs and gets only the
  unsubscribe URL.
- Add `headers` to the command: `List-Unsubscribe: <https://…/email/t/u/{token}>` and
  `List-Unsubscribe-Post: List-Unsubscribe=One-Click`.
- Expose `{{unsubscribe_url}}` to the template as a system variable.

## Public endpoints (bypass the suite-token filter and entitlement filter; scope bound from the verified token)

| Endpoint | Behaviour |
|---|---|
| `GET /email/t/o/{token}.gif` | Record `OPENED` (insert-only), return a 1×1 GIF with `Cache-Control: no-store`, always |
| `GET /email/t/c/{token}` | Look up the recorded URL by signed link id, record `CLICKED`, `302` to it; unknown or purged link → a static "link expired" page. **Never** accepts a URL parameter |
| `GET /email/t/u/{token}` | Static confirmation page that posts to the same path |
| `POST /email/t/u/{token}` | Idempotent unsubscribe: write the `MARKETING` suppression, flip the receiver, record `UNSUBSCRIBED`; this is the RFC 8058 one-click target |

Per-IP rate limit at the proxy; the application also caps events per email per minute so a scanner cannot inflate counts.

## Data model additions

- `email_links(id, tenant_id, email_id, position, url, created_at)`; purged with the body.
- `email_events` gains the types `OPENED`, `CLICKED`, `UNSUBSCRIBED`; unique key `(email_id, type)` for first-event semantics
  alongside an append-only total.

## Stats

`GET /campaigns/{id}/stats` aggregates live: `recipients`, `published`, `sent`, `failed`, `skipped`, `opened` (distinct
emails), `open_events` (total), `clicked` (distinct emails), `click_events`, `unsubscribed`. Also
`GET /emails/{id}` lists the events. Documentation states plainly that `opened` means "the pixel was loaded" and that
mail clients which prefetch (Apple Mail Privacy Protection) inflate it.

## Files to create / modify

- `email/contexts/sending/.../tracking/*` (token codec, rewriter, event recorder, unsubscribe use case, stats query)
- `email/apps/app-boot/.../web/PublicTrackingController.java`, `security/SecurityConfiguration.java` (permit the public paths
  only), `web/WebConfiguration.java`
- `email/apps/app-boot/src/main/resources/db/migration/V6__tracking.sql`; OpenAPI additions; `ops/caddy/Caddyfile` route in phase 10

## Verification

- Token tests: tampered tag, wrong tenant, wrong kind, truncated, replayed after rotation (old key id still verifies).
- Rewriter tests on real-world HTML: attribute quoting, `href` in comments, nested anchors, links with fragments; the
  rewritten HTML never contains an unrecorded external URL.
- Open-redirect test: no request shape produces a redirect to a URL not recorded at render time.
- Integration: a campaign email is opened and clicked twice; `stats` shows distinct 1 and totals 2; one-click unsubscribe
  suppresses the address and a second campaign skips it; unsubscribe is idempotent.
- Tenant isolation: a token for tenant A can never write a row under tenant B (scope comes only from the token).
- Log-capture test: no token, URL or address in logs.

## Risks

- This is the suite's first unauthenticated surface; keep the public controller tiny, in its own package, with its own
  ArchUnit rule (it may call only the tracking use cases).
- Mail clients and corporate scanners click links automatically: counts are signals, not proof of intent.
