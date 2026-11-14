---
title: Six partial states at three service edges
date: 2026-11-14
summary: "Closed six non-self-healing partial states at the tenant→billing and platform→keycloak edges, each proven by a test that fails before the fix."
---

# Six partial states at three service edges

## What happened

Implemented `plans/261114-0447-partial-state-recovery` in five phases. Every fix was verified by running its new test
against the unfixed code first, then against the fix.

- **P0-1 `ExecuteOrdersUseCase.settle`** called `release(order)` after `charge(order)`. Capturing a reservation already
  returns the unused hold and closes it, so `release` earned a 409 → local rollback → requeue → `FAILED` while the money
  was taken. Removed both calls and the method. Proof: `ExecuteOrdersSettlementIntegrationTest` failed on old code with
  `Expecting empty but was: ["res_1"]`.
- **P0-2 `BootstrapPlatformAdminUseCase`** returned early when the user row existed, so a boot that died between creating
  the user and assigning the role left an admin with zero permissions forever. It is now a repair: find-or-create the
  user, then ensure role + assignment + publish in one unit of work.
- **P0-3 `ReplicaBootstrap.needsRebuild`** only asked whether `tenants` was empty, so a rebuild that died at the second
  stream looked complete and every host 404'd. Added a `replica_state` completion marker written only after all streams
  finish, plus `V26__replica_state.sql` and regenerated jOOQ schema.
- **P1-1** compensation no longer replaces the original failure: `addSuppressed` + a warning naming the orphaned subject.
  `KeycloakIdentityProvider` now adopts an identity on 409 (deterministic `{tenant}:{email}` username) and treats 404 on
  delete as success, turning a permanently-locked email into a convergent retry.
- **P1-2/P1-3** `ManageOrderUseCase` and `ManageWalletSettingUseCase` no longer wrap a Billing call in a transaction.
  `OrderStatus.CANCELING` records the intent before the remote call; `OrderExpiryJob` retries stuck cancels.

## Decision

Three deviations from the phase text, all tightening rather than loosening it:

1. Phase 2's "user exists" lookup had to move inside a `UnitOfWork`. Row-level security reads the tenant from the
   transaction (`set_config(..., true)` is transaction-local), so the old guard's query always saw nothing — which is why
   the second boot didn't merely skip the repair but threw `ConflictException`.
2. Phase 5's sample code failed the order on a Billing error, contradicting its own success criteria ("stays CANCELING")
   and hiding the order from the reaper. Added `Order.cancelFailed(reason, now)` to record the reason without changing
   state, since `CANCELING → CANCELING` is not a transition edge.
3. The reaper uses `reservationTtl` as the cutoff for stuck `CANCELING` orders too, so it cannot race a cancel that is
   still running normally.

## Next steps

- `docker compose up` + `ops/smoke/*` not run: compose needs `.env` secrets the repo does not ship, and I did not invent
  fake ones. This is the remaining verification gap.
- The plan's Follow-up still stands: a one-off reconciliation script for already-damaged data (orders `FAILED` with a
  `SUCCEEDED` charge, admins with no grants, `tenant_domains` count off), and a replica-lag metric.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
