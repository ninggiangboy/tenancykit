---
phase: 9
title: "Rollups, reports, funnel, cohort and exports"
status: done
priority: P1
effort: "L"
dependencies: [8]
---

# Phase 9: Rollups, Reports, Funnel, Cohort and Exports

## Goal

A Tenant reads clicks, page views, sessions, conversions and credited value per link, campaign, entry, device, country,
referrer and destination, as time series, breakdowns, a funnel and a cohort view, from rollups rather than raw events, and
exports any of them as CSV.

## Files to create / modify

- Create: `attribution/contexts/insight/...` (`rollup`, `report`, `funnel`, `cohort`, `export` packages)
- Create: migration `V7__rollups.sql` (V6 is the list-query index migration): `rollup_traffic_hourly` (tenant_id, bucket,
  link_id, campaign_id, destination_id, device_class, country, entry, traffic_class, clicks, page_views, sessions,
  visitor_days), `rollup_referrers_daily` (tenant_id, day, link_id, referrer_domain, clicks),
  `rollup_conversions_daily` (tenant_id, day, campaign_id, link_id, goal_id, model, currency, conversions numeric,
  credited_value numeric, multi_touch_conversions numeric), `rollup_cursor`
- Create: `attribution/apps/app-boot/.../jobs/RollupJob` (`@Profile("worker")`)
- Modify: `contracts/openapi/attribution.yaml`, `RetentionJob` (phase 6) to read `rollup_cursor`

## API

| Method and path | Permission | Notes |
|---|---|---|
| `GET /reports/timeseries` | `report:read` | `granularity=hour\|day`, `from`, `to`, `metrics`, filters |
| `GET /reports/breakdown` | `report:read` | `dimension=link\|campaign\|entry\|device\|country\|referrer\|destination\|goal` |
| `GET /reports/funnel` | `report:read` | `campaign_id` or `link_id`, optional `goal` |
| `GET /reports/cohorts` | `report:read` | `campaign_id`, `cohort=week`, `periods` ≤ 12 |
| `GET /links/{id}/stats` | `link:read` | Totals and the last 30 days |

Any report endpoint answers `text/csv` when asked (`Accept` or `.csv`), streamed, at most 100,000 rows (`400` beyond, with
the narrower range to ask for). No export job and no object storage.

## Tasks

1. **Rollup job.** Every minute, recompute the trailing three hours for every Tenant that had events (delete and insert by
   bucket in one tenant-scoped transaction), so late events are absorbed; older buckets are final once the cursor passes
   them. Daily rollups are built from the hourly ones and the credits. All buckets are UTC; reports accept no time zone
   in v1.
2. **Traffic class.** Reports default to `HUMAN`; `include=bot,suspect` adds them, and a response field shows how many bot
   clicks were excluded.
3. **Uniques.** `visitor_days` is the number of distinct daily visitor hashes per bucket; over several days it is the sum
   of daily values and the API names it `unique_visitor_days`.
4. **Breakdown and series.** One query builder over a whitelist of dimensions and metrics; unknown names are `400`.
   Conversion metrics read credits of the current model, so changing a model changes the report after recompute.
5. **Funnel.** Three steps per entity: non-bot clicks; clicks whose click id has at least one page view (`landed`);
   clicks with a conversion credit (`converted`, optionally for one goal). Counts and step ratios.
6. **Cohorts.** Cohort = UTC week of the first click in a conversion's chain; columns = weeks since that click; cells =
   credited conversions and value. Built from `conversions.touch_chain` and credits, not raw events.
7. **Chain coverage.** Conversion reports include `multi_touch_share`, the share of conversions whose chain has more than
   one click, so a Tenant sees how far first and linear differ from last (without consent every chain is one click).
8. **Freshness.** Responses carry `data_through` (the last completed rollup time); documented as one to two minutes behind.
9. **Cardinality.** Referrer is a separate rollup so the hourly table's cardinality stays bounded; the top 50 referrers per
   link per day are kept and the rest are folded into `other`.

## Verification

- `cd attribution && ./gradlew test --tests '*insight*'` green.
- Tests: a seeded scenario (known clicks, page views, conversions across two links and two days) returns exact counts on
  every endpoint; a late event inside three hours changes the numbers on the next job run; bot clicks are excluded by
  default and included on request; tenant A sees none of tenant B's numbers; an unknown dimension is `400`; CSV output
  matches the JSON values.
- A seeded 5-million-click dataset (a generator in the test source tree, run by a `reportLoad` Gradle task outside the normal
  build) answers every report endpoint at p95 ≤ 500 ms; the measurement goes in `docs/attribution.md`.

## Risks

- UTC-only days misalign with a Tenant's local day; a client can use hourly data. Recorded as a non-goal.
- Referrer folding loses the long tail; the `other` row says so.
