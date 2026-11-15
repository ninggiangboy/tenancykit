---
phase: 10
title: "Spend: manual cost and cost metrics"
status: pending
priority: P1
effort: "M"
dependencies: [9]
---

# Phase 10: Spend: Manual Cost and Cost Metrics

## Goal

A Tenant records advertising cost per campaign and day by hand and reads CPC, CPA, ROAS and ROI next to results, with a
cost table shaped so imports in phase 11 add rows without changing it.

## Files to create / modify

- Create: `attribution/contexts/spend/...` (`entry`, `cost` packages)
- Create: migration `V7__spend.sql`: `spend_entries` (id, tenant_id, campaign_id, day, source `MANUAL|GOOGLE_ADS|META_ADS`,
  connection_id nullable, amount numeric(19,4), currency, impressions bigint nullable, clicks bigint nullable, updated_at;
  unique `(tenant_id, campaign_id, day, source, connection_id)` treating a null connection as one value)
- Modify: `contracts/openapi/attribution.yaml`; `contexts/insight` (cost report reads `spend` only through its `api` package)

## API

| Method and path | Permission | Notes |
|---|---|---|
| `PUT /campaigns/{id}/spend/{day}` | `spend:manage` | Sets the `MANUAL` row for that day; `amount` ≥ 0 |
| `POST /campaigns/{id}/spend` | `spend:manage` | Up to 366 days in one call, same rules |
| `DELETE /campaigns/{id}/spend/{day}` | `spend:manage` | Deletes the `MANUAL` row only |
| `GET /campaigns/{id}/spend` | `spend:read` | Rows with source |
| `GET /reports/costs` | `report:read` | `campaign_id?`, `from`, `to`, `group_by=campaign\|day` |

## Tasks

1. **Currency.** An entry's currency must equal the campaign's (`409 currency_mismatch` otherwise); no conversion exists.
2. **Sources add up.** A day's cost is the sum over sources. The report returns the per-source breakdown with every total so
   a double entry (the same spend typed by hand and imported) is visible. Documented in the Tenant guide.
3. **Metrics.** For a campaign and range: `cost`, `human_clicks`, `conversions` (sum of credit weights, so linear credit can
   be fractional), `credited_value`, `cpc = cost / human_clicks`, `cpa = cost / conversions`, `roas = credited_value / cost`,
   `roi = (credited_value - cost) / cost`. A zero denominator gives `null`, never `0` or an error.
4. **Credit attribution to a campaign.** Credit counts for the campaign of the clicked link that received it, whichever
   campaign owns the conversion. Conversions whose currency differs from the campaign's are excluded from value metrics and
   counted in `excluded_conversions`.
5. **Cost granularity.** Cost exists per campaign and day, so link-level CPC is not offered and the API says so.

## Verification

- `cd attribution && ./gradlew test --tests '*spend*' --tests '*cost*'` green.
- Tests: a seeded campaign with known cost, clicks and credits returns the exact CPC, CPA, ROAS and ROI; two sources on one
  day sum and are listed separately; zero clicks or zero cost give `null`; a currency mismatch is `409`; deleting a manual
  row leaves an imported row untouched; tenant isolation on every endpoint.

## Risks

- Summing sources trusts the Tenant not to double-enter; the visible breakdown is the mitigation, not a rule.
