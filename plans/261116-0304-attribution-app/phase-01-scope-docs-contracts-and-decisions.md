---
phase: 1
title: "Scope, docs, contracts and decisions"
status: done
priority: P1
effort: "M"
dependencies: []
---

# Phase 1: Scope, Docs, Contracts and Decisions

## Goal

The suite's documents and contracts describe `attribution/` before any code exists: its place in the suite, its permission
catalog, its API skeleton, the attribution test vectors and the numeric decisions every later phase measures against.

## Context

Follow the shape of `contracts/permissions/mailroom.yaml` and `contracts/openapi/mailroom.yaml`, and the touchpoint list
of `plans/261115-1849-email-app/phase-10-ops-e2e-and-docs.md`. The attribution vectors are the contract the engine in
phase 8 is built against, so they are written first.

## Files to create / modify

- Create: `contracts/permissions/attribution.yaml`, `contracts/openapi/attribution.yaml` (info, servers, security, the
  public paths and the schemas shared by several phases; later phases add their paths)
- Create: `contracts/attribution/attribution-vectors/` with `vector.schema.json` and the first vector files
  (`first-click.json`, `last-click.json`, `linear-even.json`, `linear-rounding.json`, `window-excludes-old-click.json`,
  `single-click-chain.json`, `unattributed.json`)
- Create: `contracts/attribution/ingest/` with `click.schema.json` (the rest are added in phases 7 and 8)
- Create: `docs/attribution.md` (decisions, vocabulary, the numbers below; grows each phase)
- Modify: `docs/overview-and-scope.md`, `docs/README.md`, `contracts/README.md`, root `README.md`

## Tasks

1. **Permission catalog.** Codes (all `scopes: [tenant]`): `attribution:campaign:read`, `:campaign:manage`, `:link:read`,
   `:link:manage`, `:goal:read`, `:goal:manage`, `:conversion:write` (grantable to a Tenant System: this is the postback),
   `:report:read`, `:spend:read`, `:spend:manage`, `:connection:manage`, `:settings:manage`, `:data:erase`. Not grantable to
   a Tenant System: `connection:manage` (a person authorizes an ad account), `settings:manage`, `data:erase`.
2. **Record the numbers** in `docs/attribution.md` under "Decisions", each with its measuring phase: redirect p95 ≤ 20 ms and
   p99 ≤ 50 ms at 500 requests per second for 60 s on the compose stack; raw event retention 90 days, deployment-wide;
   rollups 25 months; session 30 minutes of inactivity; visitor cookie 30 days; attribution window default 30 days
   (1-90); ingest buffer 10,000 events per instance, flush every 200 ms or 500 events; link cache TTL 30 s; report
   endpoints p95 ≤ 500 ms on 5 million seeded clicks. The Validation Log in `plan.md` holds the user's final values.
3. **Vectors.** Each file: `{name, model, window_days, currency, conversion: {at, value}, chain: [{click_id, link_id,
   campaign_id, at, traffic_class}], expect: [{click_id, credit}]}`. Cover all three models, a window that excludes an old
   click, a bot click that is skipped, a chain of one, no chain (`unattributed`), and rounding: value `100.00` over three
   touches in a 2-digit currency gives `33.33, 33.33, 33.34`; in a 0-digit currency (`VND`) `100` over three gives
   `33, 33, 34`.
4. **OpenAPI skeleton.** Security scheme for suite tokens; the public surface `/go/{slug}`, `/t/v1/*`,
   `/t/oauth/callback`; problem-details error shape the suite already uses.
5. **Docs.** Add `attribution/` to `docs/overview-and-scope.md` (deployable table, a dated entry, vocabulary: campaign,
   link, click, click id, visitor, session, goal, conversion, touchpoint, credit, model, spend). Count the deployables by
   `git grep -n -i "deployables" docs/ README.md` at the time and reword only the true number. Record in the non-goals
   that `attribution/` measures only traffic that arrived through a tracked link.
6. **Owner actions (not code).** Write in `docs/attribution.md` the two external setups phase 11 needs, so they can start
   now: a Google Ads developer token with a test manager account and an OAuth client; a Meta app with `ads_read` and a test
   ad account. Registered OAuth redirect URI: `https://platform.<base-domain>/attribution/t/oauth/callback` (one URI for
   every Tenant; the Tenant comes from the signed `state`).

## Verification

- `git grep -n -i "attribution" docs/overview-and-scope.md` shows the new row and the dated entry; the deployable count
  in prose matches the table.
- Every vector validates against `vector.schema.json` (a throwaway script or the phase 8 runner's schema test).
- `contracts/permissions/attribution.yaml` parses with the same loader the Platform catalog test uses
  (`./gradlew :apps:app-boot:test --tests '*Permission*'` in `platform/`).
- Doc files stay under 800 lines.

## Risks

- The numbers are defaults chosen before any measurement. Phase 5 and phase 12 may show they are wrong; change them in
  `docs/attribution.md` with the measurement, not silently in a test.
