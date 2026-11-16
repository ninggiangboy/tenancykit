---
phase: 10
title: "loyalty program, tiers and promotion rules"
status: pending
priority: P1
effort: "M"
dependencies: [4]
---

# Phase 10: loyalty Program, Tiers and Promotion Rules

## Goal

A Tenant configures its one points program (currency, point rate, expiry, tier window, time zone), its ordered tiers and its
promotions, and the other contexts read that configuration only through the `program` context's `api`.

## Context

`program` owns configuration and reaches no other context. There is one program per Tenant. A program is created by an explicit
`PUT /program`; nothing earns until it exists. Updates follow `If-Match` and `ExpectedVersion`
(`core/core-kernel/.../version`). Migration range `V10`.

## Files to create

- `loyalty/apps/app-boot/src/main/resources/db/migration/V10__program.sql`; regenerate the jOOQ schema
- `loyalty/contexts/program/...` features `program`, `tier`, `promotion` (domain, application, api, adapter), including the
  query port other contexts use (`ProgramView`, `TierView`, `PromotionView`)
- Tests: `ProgramApiIntegrationTest`, `TierApiIntegrationTest`, `PromotionApiIntegrationTest`, `ProgramIsolationTest`

## Tasks

1. **Program.** `loyalty_programs(tenant_id, currency, unit_amount, expiry_months, expiry_notice_days, tier_window_months, time_zone, tiers_dirty,
   version, ...)`. `unit_amount` is minor units that earn one point (for example 1000). `expiry_months` 1-120 (default 12), `expiry_notice_days` 0-365 (default 30, 0 means no notice),
   `tier_window_months` 1-60 (default 12), `time_zone` an IANA id (default `UTC`). `GET /program` answers `404
   PROGRAM_NOT_CONFIGURED` until the first `PUT`. The currency cannot change once a ledger entry exists (`409`, checked in
   phase 11 through a query port that this phase declares and phase 11 implements).
2. **Tiers.** `tiers(tenant_id, id, code, name, min_spend, earn_multiplier, position)`. `PUT /tiers` replaces the ordered list by
   `code`: existing codes keep their id, new codes get one, a removed code is allowed only if no member holds it (`409`
   otherwise, checked through a port phase 11 implements). Rules: at least one tier, the first has `min_spend` 0, `min_spend`
   strictly increases, `earn_multiplier` 1.00-10.00 with two decimals. Any change sets `tiers_dirty` on the program so phase 13
   re-evaluates every member.
3. **Promotions.** `promotions(tenant_id, id, name, status ACTIVE|PAUSED|ARCHIVED, multiplier, starts_at, ends_at, conditions
   jsonb, version)`; `multiplier` 1.01-10.00; `conditions` has optional lists `categories`, `skus`, `storeCodes`, `daysOfWeek`
   (1-7), `tierCodes` and an optional `timeFrom`/`timeTo` (local time in the program time zone). Several kinds mean all of them,
   several values in one list mean any of them, an empty or missing list means no restriction. At most 200 non-archived
   promotions. `GET|POST /promotions`, `GET|PATCH /promotions/{id}` (status, name, window, conditions, multiplier).
4. **Read port.** `program.api` exposes immutable views: the program, the tiers in order, and the promotions that are `ACTIVE`
   and whose window contains a given instant. No other context reads these tables.
5. **Permissions.** `loyalty:program:read` for `GET`, `loyalty:program:manage` for every write.

## Verification

- `cd loyalty && ./gradlew build` green.
- Tests prove: a program must exist before reads succeed; a tier list without a zero first tier, with a non-increasing
  threshold or with a multiplier of 0.5 is `422`; removing a held tier is `409`; a tier change sets `tiers_dirty`; a promotion
  with a window that ends before it starts is `422`; the view returns only active promotions covering an instant; tenant B
  cannot read tenant A's program.

## Risks

- Promotions are the part most likely to grow into a rules language. The condition set is closed (five lists and a time range);
  anything else is a new field with a vector, not a new syntax.
