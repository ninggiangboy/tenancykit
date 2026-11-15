# Attribution

The suite's app for telling a **Tenant which marketing link, channel or ad brought a customer, and what that cost**.
A Tenant creates campaigns and tracked links, shares them anywhere, and reads clicks, page views, sessions,
conversions and the credited value next to the advertising spend behind them. It is a separate deployable
(`attribution/`, root package `dev.ngb.attribution`) with three runtimes: `migrate`, `api` and `worker`.

Attribution measures only traffic that arrived through a tracked link. It is not a web-analytics product: a visitor
who never met a `/go/` link is invisible to it, and every number it reports is about the campaigns a Tenant chose to
track.

## What it is, and what it is not

| It is | It is not |
|---|---|
| The owner of a Tenant's campaigns, tracked links, destinations and rules, and the clicks, page views, sessions and conversions they produce | A link shortener: it redirects, but the redirect exists to measure, and a link is always attached to a campaign or a purpose |
| The owner of conversions, their touchpoint chains and the credit each click receives under a model | A CRM or a customer-identity system: it resolves no person across devices and joins no data to mailroom or survey |
| A publisher of nothing and a consumer of its own ingest only: it writes `attribution.ingest` and reads `platform.events` | A producer of domain events in v1, a sender of messages, or a caller of another app |
| The owner of advertising cost, entered by hand or imported from Google Ads and Meta Ads read-only | An ad manager: it never creates, edits, pauses or bids on an ad, and it only ever calls a provider's read endpoints |

The Tenant's data is the Tenant's: another Tenant's token cannot read or write it, no other app reads it, and the app
stores no raw IP address and sets no cookie before consent.

## Runtimes

| Profile | Runs | Migrates |
|---|---|---|
| `api` | HTTP: campaigns, links, QR, goals, the postback, reports, spend, connections, settings, erasure, the public redirect `/go/*` and the public collection `/t/v1/*` | no |
| `worker` | The `platform.events` listener, the replica rebuild, the ingest consumer, the partition and retention jobs, the credit and recompute jobs, the rollup job and the ad sync job | no |
| `migrate` | Flyway as the table owner, then exits | yes |
| default (no profile) | `api` and `worker` together, for dev and tests | yes |

Both long-running runtimes include `replicaState` in the readiness group. The worker is the only runtime that rebuilds
the replica, so on a fresh database `api` stays `DOWN` until the worker finishes one full rebuild.

## Contexts

Five business contexts plus a replica:

| Context | Owns |
|---|---|
| `platform` | A replica of the Platform data it enforces: `tenants`, `tenant_domains`, `tenant_systems`, `user_grants`, `app_entitlements`, plus the read ports `TenantDirectory` and `UserDirectory`. Nothing else reaches these tables |
| `links` | Campaigns (model, window, currency), tracked links, destinations and their rules, QR generation, bulk personal links, and the redirect decision |
| `tracking` | Click, page-view, session and goal events, visitor identity and consent, bot classification, the day partitions, retention and erasure |
| `attribution` | Goals, conversions, the touch chain, the three models, the credits and the recompute runs |
| `spend` | Cost entries, ad connections and mappings, the ad-platform adapters and the sync |
| `insight` | Rollups, reports, the funnel, cohorts and streamed CSV exports. It writes only its own rollup and export tables |

`tracking`, `attribution` and `insight` reach `links` only through its `api` package, `attribution` reaches `tracking`
only through its `api` package, and ArchUnit fails the build when a context names another's tables.

## Vocabulary

| Term | Meaning |
|---|---|
| Campaign | A Tenant's marketing effort: a name, a slug, a channel, one attribution model, one window and one currency |
| Link | One tracked destination a Tenant shares. It carries a unique slug, an optional campaign, UTM values, and one to ten destinations with rules |
| Click | One redirect answered. It carries a **click id**, the link, the chosen destination, the classified device and country, the referrer domain and a daily visitor hash |
| Click id | The signed token the redirect appends to the destination. `base64url(click_id ‖ key_id ‖ HMAC-SHA256(secret, click_id ‖ key_id))`, so only an id the app issued is accepted by the collection endpoints and the postback |
| Visitor | A browser with consent, identified by a random first-party cookie (default 30 days). Without consent there is no visitor and every chain is one click |
| Session | Thirty minutes of inactivity around a visitor's page views |
| Goal | A named conversion kind the Tenant reports, such as `signup` or `purchase`, with an optional default value |
| Conversion | One goal reached, reported by the Tenant's backend (postback) or the site snippet, with a value and a currency |
| Touchpoint | One click in a conversion's chain |
| Chain | The conversion's own click plus every human click bound to the same visitor inside the window, oldest first |
| Credit | The share of a conversion's value a click receives under the owning campaign's model |
| Model | `FIRST`, `LAST` or `LINEAR`, chosen per campaign |
| Owning campaign | The campaign of the **last click in the chain**. Its model and window govern the conversion, and every click in the chain is credited under it, whatever campaign it belongs to. One conversion is credited once |
| Spend | Advertising cost per campaign and day, from `MANUAL`, `GOOGLE_ADS` or `META_ADS`, with impressions and clicks when known |

## Decisions

These are the numbers every later phase measures against. They are defaults chosen before measurement; a phase that
measures differently changes the number here with the measurement, never silently in a test.

| Decision | Value | Measured in |
|---|---|---|
| Redirect latency | p95 ≤ 20 ms and p99 ≤ 50 ms at 500 requests per second for 60 s on the compose stack, zero 5xx | phase 5 (smaller run), phase 12 (full load test) |
| Raw event retention | 90 days, a **deployment** setting (`ATTRIBUTION_RAW_RETENTION_DAYS`), not a Tenant setting, because day partitions are shared across Tenants | phase 6 |
| Rollup retention | 25 months | phase 9 |
| Session | 30 minutes of inactivity | phase 7 |
| Visitor cookie | 30 days | phase 7 |
| Attribution window | Default 30 days, per campaign, 1 to 90 days | phase 4, phase 8 |
| Ingest buffer | 10,000 events per instance, flushed every 200 ms or 500 events | phase 5 |
| Link cache | 30 s TTL, single-flight loads, negative entries cached | phase 5 |
| Report latency | p95 ≤ 500 ms on 5 million seeded clicks | phase 9 |

Two rules follow from the privacy decisions:

- **No raw IP is stored.** The daily visitor hash is `HMAC(dayKey, tenant ‖ ip ‖ ua)` with `dayKey = HMAC(masterKey,
  yyyy-MM-dd)`, so a hash differs each UTC day and a hash alone cannot link a person across days. A schema test fails
  the build if any table gains a column named or typed like an address, or one that stores a user-agent string.
- **No cookie before consent.** Before consent the snippet sends one landing page view carrying the click id from the
  URL and nothing else. After consent it sets the visitor and session cookies. The server enforces the flag: when the
  client says no consent, `visitor_id` and `session_id` are dropped before the event is published.

## Cross-plan coordination

This plan creates `core/core-visitor` (device class, browser family, traffic class, referrer domain, GeoIP country and
the daily visitor hash) because no such code exists in the repository, and `survey/`'s plan would otherwise build the
same code inside `survey/`. If `survey/` has already built its own classifier when this module is created, the code is
extracted here and `survey/` is moved onto it in the same change. This plan does not edit the survey plan.

## Owner actions for the ad-platform import

Phase 11 imports cost from Google Ads and Meta Ads, and each needs an approval that takes time to obtain. Both can be
started now, before any of that code exists:

1. **Google Ads.** A developer token (test access is enough to build; basic access is needed for a real account), a
   test manager account, and an OAuth client. Record the OAuth scope the client requests: the Ads scope is believed to
   allow writes as well as reads, so "read-only scope" is not promised; the constraint is that only read endpoints are
   called, enforced by a guard and a test.
2. **Meta Ads.** An app with the `ads_read` permission and a test ad account.

Both providers need one exact OAuth redirect URI, and every Tenant has a different host, so the callback is a single
public URL on the Platform host:

```text
https://platform.<base-domain>/attribution/t/oauth/callback
```

It is the one place the Tenant comes from a server-signed `state` rather than the host. The state is HMAC-signed with a
secret separate from the click-token secret, expires in ten minutes, carries a one-time nonce, and any failure answers
one constant `400` that reveals nothing.

## Documents this app owns

| Document | Owns |
|---|---|
| [contracts/permissions/attribution.yaml](../contracts/permissions/attribution.yaml) | The app's permission catalog: thirteen tenant-scope codes |
| [contracts/openapi/attribution.yaml](../contracts/openapi/attribution.yaml) | The REST surface, a resource skeleton each phase fills |
| [contracts/attribution/](../contracts/attribution/README.md) | The attribution vectors the credit engine must reproduce, and the ingest schemas |
