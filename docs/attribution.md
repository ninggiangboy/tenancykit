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

## The redirect

`GET /go/{slug}` is the one endpoint a visitor waits for. The tenant comes from the host; the slug resolves from an
in-process cache (30 s TTL, single-flight load, negative entries cached, so a flood of random slugs reaches the database
once per slug per TTL). A link that exists but is paused or archived answers the same constant `404` as an unknown slug,
so a probe learns nothing; an expired link or one at its click cap answers `410`; a password-protected link answers
`401` and takes its password by `POST /go/{slug}`, with failed attempts limited before BCrypt runs.

A redirect answers `302` with the destination, the link's UTM values merged over the ones already in its query, and the
signed click id appended under the Tenant's click parameter (`aid` by default). The token is
`base64url(click_id ‖ key_id ‖ HMAC-SHA256(secret, click_id ‖ key_id))`; the key id travels inside it, so rotating the
key keeps links already in the wild valid. `HEAD` answers where a `GET` would send a visitor without appending a token
and without spending a capped link's click.

The redirect writes nothing to the database. It puts a click on a bounded in-memory buffer (10,000 events, dropped
oldest and counted under `attribution.ingest.dropped` when full) and a single flusher publishes batches of 500 or every
200 ms to `attribution.ingest`, waiting for publisher confirms. A capped link is the one exception: it does a single
conditional `update … where clicks_counted < cap` so its cap is exact under concurrency, and that is the documented
throughput cost of a cap. When the buffer overflows or the broker is down, the visitor still receives the `302`; only a
capped link can answer `410` because its counter could not be spent.

Every public limit is a per-process token bucket, so with N instances the effective limit is N times the setting. No
address, user agent, token, destination query or password is logged; the click carries a device class, a browser
family, a country, a referrer domain and the daily visitor hash, and nothing else about the person.

## Ingest storage

Clicks live in `click_events`, partitioned by day on `occurred_at` so retention drops a partition rather than deleting
rows. The worker consumes the ingest queue in batches, groups each batch by tenant and inserts each group in its own
tenant-scoped transaction — row-level security binds a transaction to one tenant, so that grouping is the only correct
shape, not an optimisation. The insert is `on conflict (event_id, occurred_at) do nothing`, so a redelivered batch
writes nothing twice.

A tenant group that fails is retried, then bisected until the one row that cannot be stored is isolated; that row is
dead-lettered to `suite.dlx` and counted, and the healthy rows around it still commit. An unparseable payload is
discarded and counted, never redelivered.

The app role owns no table and cannot create a partition, so partition DDL goes through two `SECURITY DEFINER`
functions owned by the migration owner, `attribution_ensure_partitions` and `attribution_drop_partitions`, with a fixed
`search_path` and a table-name allow list. `PartitionJob` keeps a week of partitions ahead; a default partition catches
a stray row and its row count is a gauge, because any row there means a partition was missing at insert time. Retention
(`ATTRIBUTION_RAW_RETENTION_DAYS`, default 90) is a deployment setting, not a Tenant setting, because partitions are
shared across Tenants; `RetentionJob` drops a day only once the rollup cursor has passed it, and drops nothing while no
rollup exists.

The stored facts are the classified ones. A schema test reads `information_schema` from the real migrations and fails
the build if any column is named or typed like an address (`ip`, `inet`, `cidr`, `remote_addr`, …) or holds a user-agent
string.

## Site collection

A Tenant adds one script, `/t/v1/snippet.js` (static, cacheable for a day, under 4 KB gzipped). The snippet reads the
click parameter from the landing URL, and exposes `attribution.consent(true|false)`, `attribution.page()` and
`attribution.goal(key, {value, currency})`. It sets **no cookie** before consent: the first page view carries only the
click id. After consent it keeps the click id, a random visitor id (30 days) and a session id (30 minutes of
inactivity), and sends with `sendBeacon` falling back to `fetch`.

`POST /t/v1/events` accepts at most 20 events in at most 8 KiB. An event is stored only when its click id is a token
this app signed, its `Origin` is one the Tenant listed in `allowed_origins`, the per-click daily budget (500) and the
per-visitor-hash rate hold. Everything else answers `204` and stores nothing — a bad signature, a foreign origin, an
oversize body and a rate refusal all look the same, so a probe learns nothing.

Consent is enforced on the server: with `consent=false` the use case drops the visitor and session ids before the row
is written, so no identifier reaches storage even if a client sent one. The path is stored without its query string or
fragment, so a landing page's own parameters and a search term never persist.

An event with a visitor id and a click id upserts a `visitor_clicks` row — the only link between a visitor and the
clicks that brought them, and what the multi-touch chain reads. A session upserts by `(tenant, session_id)`, extending
its last activity and counting the page.

`POST /api/v1/data-subjects/erasure` removes one visitor's page views, sessions and bindings, and the clicks no other
binding still names. Rollups hold aggregates only and are untouched, so a report's totals stay and the person does not.
`GET/PUT /api/v1/settings` manage the click parameter, the allowed origins and the default window; an origin must be an
absolute `https` origin (or `http://localhost`), and a wildcard, a path or a bare host is refused.

## Conversions and the attribution engine

A goal is a conversion kind a Tenant names by a stable key (`purchase`, `signup`), with an optional default value and
currency. A conversion is reported by the Tenant's backend (the postback, `POST /api/v1/conversions`) or by the site
snippet's `goal(key)`. The postback verifies the signed click token; a token that does not verify is stored
**`UNATTRIBUTED`** rather than rejected, because a Tenant's order flow must not fail on its own reporting. Only a
postback that names neither a click nor a visitor is a client error.

A postback is idempotent on `(tenant, Idempotency-Key)`: the same key and body returns the first conversion with `200`,
and the same key with a different body is `422`. A conversion waits a settle delay (five minutes by default) before it is
credited, so a page view or binding that names the same visitor from a slightly later click is still inside the chain.

The chain is the conversion's own click plus every **human** click bound to the same visitor inside the window before
the conversion, deduplicated and capped at 200. Bots and suspect clicks are filtered out. The campaign of the **last
click in the chain** owns the conversion: its model and window govern it, and every click in the chain receives credit
under that model, whatever campaign it belongs to — one conversion is credited once.

The three models are `FIRST`, `LAST` and `LINEAR`. All arithmetic is `BigDecimal` at the currency's fraction digits;
linear credit floors each share and gives the residual to the last touch, so the credits sum exactly to the value
(`10.00` over three is `3.33, 3.33, 3.34`). The engine is written against
`contracts/attribution/attribution-vectors/`: adding model behaviour means adding a case there, and the vector runner
fails the build when the engine does not reproduce one.

The chain is snapshotted on the conversion the first time credit is computed, so a recompute after the raw click
partitions are dropped still reproduces the same credits. Changing a campaign's model or window enqueues a recompute run,
which pages the campaign's conversions by id and replaces each conversion's credits atomically. One run per campaign at a
time; an interrupted run resumes from its cursor. Erasure removes a visitor's page views, sessions and bindings, and
strips the visitor and click from their conversions while the amount and credits stay.

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
