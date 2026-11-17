---
title: "Attribution phases 9-10: rollups, reports and manual spend"
date: 2026-11-16
summary: "Implemented the insight rollups/reports/funnel/cohorts/CSV and spend cost metrics with migrations, tests, contract and docs."
---

# Attribution phases 9-10: rollups, reports and manual spend

## What happened

- Added `V7__rollups.sql` (`rollup_traffic_hourly`, `rollup_referrers_daily`, `rollup_conversions_daily`, `rollup_cursor`) and `V8__spend.sql` (`spend_entries`).
- Built the `insight` context: rollup store and `RunRollupUseCase`, report/funnel/cohort queries, CSV writer, report and link-stats controllers.
- Built the `spend` context: manual cost use cases, read side, `CostReader` api and the cost report in insight.
- Added cross-context api seams: `tracking.api.traffic.TrafficSource`, `attribution.api.conversion.ConversionRollupSource`, `links.api.link.LinkMetadataReader`, `spend.api.cost.CostReader`.
- Wired `RollupJob`, replaced the inert `RollupCursor` with the persisted cursor, and extended `RowLevelSecurityTest` and the architecture table-owner map for the new tables.
- Regenerated the committed jOOQ schema and `contracts/openapi/attribution.yaml`; updated `docs/attribution.md`.

## Decisions

- Migrations are V7/V8 because V6 is the list-query index migration.
- The rollup job backfills from the cursor (or the 90-day raw horizon on a first run) before advancing the cursor, so retention cannot drop a day no run absorbed.
- `conversions` in the conversion rollup is the sum of credit weights, not a plain count, so a cross-campaign chain contributes each campaign's share.
- The referrer rollup folds the long tail into `other` and merges a real `other` domain with the folded row to keep the unique key valid.
- The link statistics endpoint moved from `links` to `insight`; links exposes only `LinkMetadataReader`, avoiding a dependency cycle.

## Evidence

- `./gradlew :apps:app-boot:test` green (full app suite).
- `./gradlew test --tests '*insight*'` green.
- `./gradlew test --tests '*spend*' --tests '*cost*'` green.
- `./gradlew :apps:app-boot:reportLoad -Dattribution.load.clicks=200000` green; the 5M run was blocked by the Docker VM's disk, not by a code failure.

## Limitations

- The full 5M `reportLoad` measurement could not complete on this machine because the Docker disk filled during the seed; the target remains p95 ≤ 500 ms and the 200k run passes.
- The working tree contains unrelated uncommitted changes from the list-query rollout (core query/OpenAPI, ops, tenant). Two compile errors in those files (`JooqLists` int-to-Long and an unchecked-warning chain) were fixed to keep the build running.
- The full app suite intermittently fails `SiteCollectionIntegrationTest` when Docker runs out of disk after many containers; the test passes in isolation after pruning ephemeral testcontainers.

## Next steps

- Phase 11 (Google Ads and Meta Ads import) and phase 12 (ops, load test, e2e, docs).
- Run the full 5M `reportLoad` on a machine with more Docker disk and record the measured p95 in `docs/attribution.md`.
- Review and commit the attribution changes separately from the pre-existing list-query work.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
