---
title: Attribution app advised and planned
date: 2026-11-16
summary: Scoped attribution/ via /ak-advise (links, measurement, conversions, ad cost) and wrote a 12-phase plan; the red-team pass removed three designs the suite could not support
---

# Attribution app advised and planned

## What happened
- /ak-advise took "ads, links, marketing" and narrowed it: the core is tracked marketing links and measurement, not creating ads. The user chose the heavier option at each fork: page views and sessions through a JS snippet, first/last/linear models per campaign, and cost import from both Google Ads and Meta Ads in v1. The advisor's lighter recommendations were recorded as trade-offs, not applied.
- Name `attribution` (rejected `marketing`: it collides with mailroom's `MARKETING` suppression). Standalone: the destination is any URL, no call to or from survey or mailroom.
- Wrote plans/261116-0304-attribution-app/ (12 phases) with a path-and-line verification pass, a red-team self-review and four validation questions, all answered with the recommended default.

## Verified findings that changed the design
- `SecretCipher` and `AesGcmSecretCipher` already exist in `core/`, so ad-platform tokens need no new crypto.
- No user-agent, GeoIP or bot code exists anywhere, and survey's plan would build it inside `survey/`; a shared `core/core-visitor` is planned instead.
- The app role cannot create partitions, so partition DDL goes through `SECURITY DEFINER` functions. A cross-tenant ingest batch cannot be inserted under one RLS setting, so batches are grouped by tenant.
- Providers need one exact OAuth redirect URI but Tenants have different hosts, so the callback is one URL on the Platform host and the Tenant comes from a signed `state`.
- Google's Ads OAuth scope is believed to allow writes, so the advice's "read-only scopes" cannot be promised; a read-only HTTP guard is promised instead (unverified, phase 11 task 1 checks).
- A Platform-administrator `BLOCKED` link status needed a cross-tenant write the suite has no pattern for; removing the Tenant's entitlement is the lever instead.

## Decision
- Click id is an HMAC-signed token appended to the destination; only signed click ids count. Redirect writes nothing to the database (cache, bounded buffer, broker, batch consumer). Raw events are day-partitioned with deployment-wide 90-day retention; rollups 25 months. The owning campaign of a conversion is the campaign of the last click in its chain. Reports are UTC only and CSV is streamed.

## Next steps
- Start the Google Ads developer token and the Meta app now; phase 11 needs them.
- Run phase 1 (contracts, vectors, numbers) and phase 2 (`core-visitor`) in parallel with phase 3.
- When survey phase 6 is reached, adopt `core-visitor` (a one-line edit to that phase; not made here).

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
