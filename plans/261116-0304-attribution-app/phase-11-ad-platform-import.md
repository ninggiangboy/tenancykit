---
phase: 11
title: "Ad platform import: Google Ads and Meta Ads"
status: pending
priority: P1
effort: "L"
dependencies: [10]
---

# Phase 11: Ad Platform Import: Google Ads and Meta Ads

## Goal

A Tenant connects a Google Ads or Meta ad account through OAuth, maps the platform's campaigns to its own, and the app pulls
daily cost, impressions and clicks into `spend_entries` under the platform's own source, without ever changing anything on
the platform.

## Context

This phase needs external setup that phase 1 asked the owner to start: a Google Ads developer token (test access is enough to
build) with an OAuth client, and a Meta app with `ads_read`. If neither exists when the phase starts, stop and ask whether to
ship without import or wait. Provider details below are my understanding at planning time and are not verified against
current documentation: task 1 verifies and records them.

## Files to create / modify

- Create: `attribution/contexts/spend/.../connection/` (`AdConnection`, `AdSpendSource` port, `SyncService`),
  `.../adapter/google/GoogleAdsSpendSource`, `.../adapter/meta/MetaAdsSpendSource`, `.../adapter/ReadOnlyHttpGuard`
- Create: migration `V8__ad_connections.sql`: `ad_connections` (id, tenant_id, platform `GOOGLE_ADS|META_ADS`,
  external_account_id, account_time_zone, account_currency, status `ACTIVE|REAUTH_REQUIRED|REVOKED`, secret_ciphertext,
  token_expires_at nullable, last_synced_at, last_error), `ad_campaign_mappings` (connection_id, external_campaign_id,
  campaign_id; unique both ways), `ad_sync_runs` (connection_id, from_day, to_day, status, rows, error)
- Create: `attribution/apps/app-boot/.../jobs/AdSyncJob` (`@Profile("worker")`), `.../web/OAuthCallbackController` (public)
- Modify: `contracts/openapi/attribution.yaml`, `docs/attribution.md`, `.env.example`

## API

| Method and path | Auth | Notes |
|---|---|---|
| `POST /connections/{platform}/authorize` | `connection:manage` | Returns `{authorization_url}`; `state` is HMAC-signed with tenant, actor, platform, nonce, `return_url`, expiry (10 minutes) |
| `GET /t/oauth/callback?code&state` | public | The one endpoint whose tenant comes from the signed `state`, not the host |
| `GET /connections`, `GET /connections/{id}` | `connection:manage` | Never returns a token |
| `GET /connections/{id}/external-campaigns` | `connection:manage` | Lists the platform's campaigns for mapping |
| `PUT /connections/{id}/mappings` | `connection:manage` | `{external_campaign_id, campaign_id}` list; `409` on currency mismatch |
| `POST /connections/{id}/sync`, `DELETE /connections/{id}` | `connection:manage` | Delete revokes locally and best-effort at the provider |

## Tasks

1. **Verify provider facts first** with current documentation (use `ak-docs-seeker` or the providers' pages) and record in
   `docs/attribution.md`: API versions pinned in configuration; Google's OAuth scope and whether any read-only scope exists;
   the `searchStream` query for campaign cost, impressions and clicks by date and the unit of `cost_micros`; Meta's token
   lifetime and the `insights` parameters for daily campaign spend; each provider's rate limits and the error that means
   "token revoked". Change the tasks below to match what is found.
2. **Port.** `AdSpendSource` offers `authorizationUrl`, `exchangeCode`, `listCampaigns`, `fetchDailySpend(from, to)` returning
   `SpendRow(day, externalCampaignId, amount, currency, impressions, clicks)` and `accountInfo()` (time zone, currency).
   Differences stay inside each adapter: Google micros and customer time zone; Meta string amounts, account time zone,
   long-lived token expiry.
3. **HTTP.** Spring `RestClient` against the providers' REST APIs (no vendor SDK). `ReadOnlyHttpGuard` is an interceptor that
   allows `GET`, the token endpoint, and Google's read-only query paths, and throws on anything else, so a mutate call cannot
   be added by accident; a unit test proves it.
4. **Tokens.** Stored with `SecretCipher` using the connection id as context. Never logged. Revoked or invalid tokens set
   `REAUTH_REQUIRED`, stop syncing and show in `GET /connections`. Meta's long-lived token carries `token_expires_at`; the
   job marks `REAUTH_REQUIRED` a day before it lapses.
5. **Sync.** Daily and on demand. The first sync pulls the previous 30 days; later runs re-pull the last 7 days because
   platforms revise recent cost. Upsert by `(tenant, campaign, day, source, connection)`; never touch `MANUAL` rows. A
   `429` or a transient error backs off and retries; a permanent error ends the run with `last_error`. Account-local days
   are stored as given and the account time zone is shown, so the day boundary difference from UTC reports is visible.
6. **Mappings.** A mapping is refused when the platform account's currency differs from the campaign's currency. Unmapped
   platform campaigns are ignored, not imported.
7. **Callback.** Verify `state` (signature, expiry, one-time nonce stored), exchange the code, store the connection, then
   `302` to `return_url` if its origin is in the Tenant's `allowed_origins`, otherwise `200` JSON. Any failure is a constant
   `400` that reveals nothing.
8. **Fixtures.** Record each provider's responses from the test or sandbox account into test resources (credentials
   removed) and drive the adapters from a local mock server; a separate manual checklist in `docs/attribution.md` is run once
   against the real test accounts.

## Verification

- `cd attribution && ./gradlew test --tests '*spend*' --tests '*adapter*'` green against the recorded fixtures.
- Tests: the OAuth state of another tenant, an expired state and a replayed state are refused; a sync upserts without
  duplicates and leaves a `MANUAL` row for the same day untouched; a revoked token flips the connection to
  `REAUTH_REQUIRED` and stops retries; a currency mismatch on mapping is `409`; `ReadOnlyHttpGuard` blocks a mutating call;
  no ciphertext, token or `code` appears in logs (a log-capture test).
- Manual checklist (once, with the real Google and Meta test accounts): connect, map, sync, see rows, revoke, see
  `REAUTH_REQUIRED`. Record the date and the result in `docs/attribution.md`.

## Risks

- Approvals may be late or denied: the phase is last among functional phases and may be shipped later without touching
  anything else (the cost table already holds the sources).
- Google's OAuth scope grants more than the feature uses (to verify in task 1). Mitigations: encryption bound to the
  connection, read-only guard, revoke on delete, and the scope stated plainly on the consent flow in the docs.
- The single registered redirect URI sits on the Platform host; the signed state is the only tenant binding there, so its
  secret is separate from the click-token secret (`ATTRIBUTION_OAUTH_STATE_SECRET`).
