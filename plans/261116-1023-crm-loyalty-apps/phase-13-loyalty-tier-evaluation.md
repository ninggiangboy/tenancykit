---
phase: 13
title: "loyalty tier evaluation"
status: done
priority: P2
effort: "M"
dependencies: [11]
---

# Phase 13: loyalty Tier Evaluation

## Goal

A member's tier rises in the transaction of the earn that crosses a threshold, falls only in the scheduled evaluation, and is
re-evaluated for everyone when the Tenant changes its tiers.

## Context

Qualifying spend is read from the earn records phase 11 keeps (`order_earns.spend_amount` by `occurred_at`), so `tiers` has no
spend table of its own: it reads `points.api` and never writes points tables except through `points.api`. The rise-only check
reaches `tiers` through the `TierEvaluator` port of phase 11, wired in app-boot, so the context graph has no cycle. Migration
range `V40` (indexes only, if any).

## Files to create

- `loyalty/contexts/tiers/...` (domain `TierPolicy`, application `TierEvaluationService`, adapters); the app-boot adapter that
  implements `TierEvaluator`; `loyalty/apps/app-boot/.../jobs/TierEvaluationJob.java`
- `loyalty/apps/app-boot/src/main/resources/db/migration/V40__tier-review.sql` if an index is missing
- Tests: `TierPolicyTest`, `TierEvaluationIntegrationTest`, `TierBoundaryVectorTest`

## Tasks

1. **Spend.** `S(member, now)` is the sum of `spend_amount` of the member's orders whose `occurred_at` is within the last
   `tier_window_months` calendar months in the program time zone (month arithmetic clamps to the month end). Returns already
   reduce `spend_amount` because the earn record holds the net figure.
2. **Tier choice.** The highest tier whose `min_spend` is at most `S`. Tier multipliers affect future earns only.
3. **Rise on earn.** After an earn or return is applied, the app-boot adapter calls `tiers.api` to compute the tier; if it is
   higher than the member's, set it in the same transaction. It never lowers a tier. A return that lowers `S` sets
   `tier_review_at = least(tier_review_at, now)` so the next evaluation looks at the member.
4. **Scheduled evaluation.** `TierEvaluationJob` (per-tenant sweep, daily by default, a setting) picks members with
   `tier_review_at <= now` in bounded batches; per member, in its own short transaction and after the member lock, recompute
   the tier (rise or fall) and set `tier_review_at` to the moment the oldest in-window order ages out of the window, or `null`
   when no order is in the window. The member's tier is set only if it changed.
5. **Tier changes.** When a Tenant replaces its tiers (`tiers_dirty` is set, phase 10) the job first sets `tier_review_at = now`
   on the Tenant's members in id-ordered batches with a cursor, then clears the flag, so a restart resumes rather than repeats.
6. **Balance field.** Fill `nextTier` in the member response: the next higher tier's code, `minSpend` and the remaining spend,
   or `null` at the top.
7. **Boundary vectors.** Cases in `contracts/loyalty/earn-vectors/` or a sibling `tier-vectors/`: spend exactly at a threshold;
   an order exactly `N` months old; 31 January minus one month; a time zone whose midnight differs from UTC; a return that
   drops a member below a threshold (rise is immediate, fall waits for the job).

8. **Webhooks.** `tier.changed` with `UP` from the rise-on-earn path and `DOWN` from the scheduled evaluation, in the
   transaction that writes the new tier; no event when the tier is unchanged.

## Verification

- `cd loyalty && ./gradlew build` green.
- Tests prove: crossing a threshold changes the tier in the same call that records the order; a return does not lower the tier
  until the job runs and then does; changing the tiers re-evaluates all members and survives a job restart mid-way; a member
  with no in-window orders falls to the base tier; `tier_review_at` is `null` when nothing can change.

## Risks

- Calendar-month arithmetic in a time zone is where off-by-one bugs live: the boundary vectors are the guard.
- A Tenant with a million members needs the batched, cursor-resumed path in task 5; the test uses 10,000 members with a small
  batch size to exercise resume.
