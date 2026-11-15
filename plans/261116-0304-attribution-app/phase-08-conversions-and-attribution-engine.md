---
phase: 8
title: "Conversions, postback and attribution engine"
status: pending
priority: P1
effort: "L"
dependencies: [7]
---

# Phase 8: Conversions, Postback and Attribution Engine

## Goal

A conversion reported by the Tenant's backend or the site snippet is stored once, credited to the clicks that led to it
under the owning campaign's model, reproducibly, and re-credited when the model or window changes.

## Files to create / modify

- Create: `attribution/contexts/attribution/...` (`goal`, `conversion`, `chain`, `model`, `credit`, `recompute` packages)
- Create: migration `V5__conversions_and_credits.sql`: `goals` (id, tenant_id, key, name, default_value, status; unique
  `(tenant_id, key)`), `conversions` (id, tenant_id, goal_id, click_id nullable, visitor_id nullable, value numeric(19,4),
  currency, occurred_at, source `POSTBACK|SNIPPET`, idempotency_key, credit_status `PENDING|COMPUTED|UNATTRIBUTED`,
  owning_campaign_id nullable, touch_chain jsonb, computed_at; unique `(tenant_id, idempotency_key)`), `conversion_credits`
  (conversion_id, click_id, link_id, campaign_id, model, weight numeric(9,8), credit numeric(19,4); primary key
  `(conversion_id, click_id)`), `recompute_runs` (id, tenant_id, campaign_id, reason, status, cursor, started_at,
  finished_at)
- Create: `attribution/apps/app-boot/.../jobs/CreditJob`, `RecomputeJob` (`@Profile("worker")`)
- Create: `attribution/contexts/attribution/src/test/resources` loader for `contracts/attribution/attribution-vectors/`
- Modify: `contracts/openapi/attribution.yaml`, `contracts/attribution/ingest/goal.schema.json`, ingest consumer (goal
  events become conversions), `ErasureService` (phase 7) for conversions

## API

| Method and path | Permission | Notes |
|---|---|---|
| `POST/GET/PATCH /goals`, `GET /goals/{id}` | `goal:manage` / `goal:read` | `key` `[a-z0-9_-]{1,64}` |
| `POST /conversions` | `conversion:write` | The postback. `Idempotency-Key` required; body `{goal_key, click_id \| visitor_id, value?, currency?, occurred_at?}`; `201` or `200` on replay |
| `GET /conversions`, `GET /conversions/{id}` | `report:read` | Includes the touch chain and credits |

## Tasks

1. **Goals.** The snippet's `goal(key)` must name an existing goal; an unknown key is ignored and counted. `value` defaults
   to the goal's default; `currency` defaults to the owning campaign's currency.
2. **Postback.** Verifies the click token with `ClickTokens` (a forged or foreign-tenant token is stored as
   `UNATTRIBUTED` with reason `invalid_click`, not rejected, so a Tenant's order flow never fails here). Idempotent on
   `(tenant, Idempotency-Key)` with the suite's fingerprint rule: same key and body replays, same key and different body
   is `422`.
3. **Snippet goals.** An ingested `goal` event becomes a conversion with `source = SNIPPET` and idempotency key = event id.
4. **Chain.** For a conversion, the chain is the conversion's own click plus every human click bound to its visitor id
   (`visitor_clicks`) within 90 days before it, not after it, deduplicated and capped at 200. Stored as `touch_chain` the
   first time credit is computed, so a recompute after raw events are dropped still reproduces the result.
5. **Owning campaign.** The campaign whose model and window govern the conversion is the campaign of the last click in
   the chain. The chain may include clicks from other campaigns, which receive credit under that model.
6. **Models.** `FIRST` gives all credit to the first click inside the window; `LAST` to the last; `LINEAR` splits evenly.
   Credits are rounded to the currency's fraction digits (`java.util.Currency`), with the residual added to the last touch
   so the credits sum exactly to the value. A pure function `credit(chain, model, window, value, currency)`.
7. **Settle delay.** `CreditJob` takes `PENDING` conversions older than five minutes (`for update skip locked`), builds the
   chain, writes credits and sets `COMPUTED` or `UNATTRIBUTED`, in one transaction per conversion. Late-arriving bindings
   after that are not re-evaluated except by a recompute. Documented.
8. **Recompute.** Changing a campaign's model or window (phase 4's `PATCH`) inserts a `recompute_runs` row. `RecomputeJob`
   processes the campaign's owned conversions by id with a cursor, one run per campaign at a time (row lock), replacing a
   conversion's credits atomically. It reads `touch_chain`, never raw events.
9. **Vectors.** The runner loads every file in `contracts/attribution/attribution-vectors/` and asserts the credits; adding
   a vector is the only way a model test is added.

## Verification

- `cd attribution && ./gradlew test --tests '*attribution*' --tests '*conversion*'` green.
- Tests: all vectors pass (first, last, linear even, linear rounding, window exclusion, bot skipped, single click,
  unattributed); the same idempotency key twice yields one row and a `200`; a different body with that key is `422`; a
  forged click token gives `UNATTRIBUTED` and no failure; a recompute from `LAST` to `LINEAR` after the raw partition is
  dropped yields the vector's credits; two concurrent recomputes of one campaign run one after the other; erasure nulls the
  conversion's visitor and click and its credits become `UNATTRIBUTED`.

## Risks

- The owning-campaign rule is a product decision (see Validation Log): a chain across campaigns credits clicks of campaigns
  whose own model differs. Document it in the Tenant guide with an example.
- The chain depends on consent: without a visitor id the chain is one click, so all models agree. Reports say so.
