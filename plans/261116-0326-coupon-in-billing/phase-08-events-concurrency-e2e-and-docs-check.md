---
phase: 8
title: "Phase 8: Events, concurrency, end-to-end and docs check"
status: done
priority: P1
effort: "L"
dependencies: [1, 2, 3, 4, 5, 6, 7]
---

# Phase 8: Events, concurrency, end-to-end and docs check

## Goal

Prove the delivered system against the plan's Done means: isolation, concurrency under the real lock order, every charge
path end to end, the contract set in step, and the documentation true to the code.

## Context

- `docs/concurrency-and-locking.md` owns lock order; `DeadlockStressIntegrationTest` is the existing stress test.
- Isolation tests live under `billing/apps/app-boot/src/test/java/dev/ngb/billing/rls`; end-to-end helpers are in
  `.../support/HttpWorld.java`.
- Event types are compared with schema files by a test per producer; webhook endpoints may enumerate event types elsewhere.

## Files

- Modify: `billing/apps/app-boot/src/test/java/dev/ngb/billing/concurrency/DeadlockStressIntegrationTest.java` (coupon flows)
- Create: coupon isolation tests under `.../rls/`, end-to-end tests under `.../coupon/`
- Modify: `docs/concurrency-and-locking.md` (the two new lock rows with the order reasoning), `docs/coupons.md`
  and the other topic documents so each claim matches the code
- Modify: any registry that lists billing event types (find with
  `grep -rn "invoice.paid" billing core contracts tenant --include='*.java' --include='*.yml' --include='*.json'`), and add
  `coupon.redeemed` and `coupon.redemption-ended` where an event list is meant to be complete; an end-to-end test
  subscribes a webhook endpoint to both and sees redeem, exhaustion and revoke delivered
- Create (if the existing smoke scripts cover billing flows): one smoke step for redeem then charge under `ops/smoke/`

## Steps

1. Concurrency, in this order:
   - 20 parallel redeems of a coupon with 1 redemption left: exactly one succeeds, the rest answer the limit error.
   - 20 parallel charges on 20 wallets sharing a coupon with a total-discount budget: the sum of discounts equals the budget
     (or less), never more, and every charge's lines match what was consumed.
   - Charges, redeems, revokes and renewals interleaved with the existing stress mix: no deadlock, no lock-timeout answers
     above the existing tolerance.
2. Isolation: tenant A cannot read, redeem, revoke or discount with tenant B's coupons, codes or redemptions, on every table
   and endpoint; a Tenant System cannot redeem onto an unmapped customer's wallet.
3. End to end, for each of usage, renewal, reservation capture and standalone, on a Prepaid and a Postpaid wallet: the
   70,000 case, ledger debit equal to the net, the Charge's gross and lines, and for Postpaid the unbilled line equal to
   the net; a 100% case produces a zero-net charge, no ledger entry and no invoice line.
4. Failure cases: insufficient funds consumes nothing; a replayed charge consumes once; revoke mid-flight changes the next
   charge only; credit coupon credits once on retry.
5. Documentation check: read each topic document changed in this work and verify every claim against the source or a test
   (permissions, events and fields against the schemas, lock order against the code paths, examples against the golden
   tests). Fix the document, not the test, unless the code is wrong.
6. Load check on the compose stack (or the existing load test harness under `ops/loadtest`): charge throughput with and
   without a budgeted coupon on one hot coupon; record the numbers in the docs only if a limit is found.
7. Run the whole build and the contract regenerations once more; confirm nothing under `platform/`, `tenant/`, `mailroom/`,
   `survey/`, `attribution/`, `notification/` or `mailer/` changed, and `core/` changed only by the event constant.
8. Write a short journal entry with `/ak-journal` if the repository's workflow asks for one.

## Todo

- [x] Concurrency tests green, stress mix extended
- [x] Isolation tests green
- [x] End-to-end matrix green (four paths, two wallet modes)
- [x] Failure cases green
- [x] Event type registries and smoke updated where they exist
- [x] Documentation re-verified against source
- [x] Load check recorded
- [x] Full build green; diff boundary confirmed

## Verification

- `cd billing && ./gradlew build`
- `cd billing && ./gradlew :apps:app-boot:test --tests '*OpenApiContractTest' --tests '*PermissionCatalogTest' --tests '*JooqSchemaTest'` leaves `git status` clean (no contract or generated-code drift)
- `git diff --stat dev...HEAD -- platform tenant mailroom survey attribution notification mailer` is empty; `git diff --stat -- core` shows only `SuiteEventTypes.java`

## Risks

- A flaky timing-based concurrency test: use latches to start the contenders together and assert on counts, not on timing.
- Load numbers differ on a laptop; the check looks for a cliff (a hot coupon serializing all wallets), not a benchmark.

## Security considerations

The isolation matrix is the security proof for this feature: every new table and endpoint is exercised across two tenants,
and the Tenant System mapping rule is exercised for redeem and for estimates with a wallet.
