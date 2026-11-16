---
phase: 14
title: "loyalty point expiry"
status: done
priority: P2
effort: "M"
dependencies: [11]
---

# Phase 14: loyalty Point Expiry

## Goal

Points expire lot by lot on schedule without ever overspending or double-expiring a lot, a member can see what is about to
expire, and the Tenant is told once per lot, ahead of time, through `points.expiring`.

## Context

Correctness never depends on when the job runs: a lot with `expires_at` in the past is already excluded from `available` and
from spend (phase 12). The job only makes the ledger say so. It follows the lock order of every flow: member first, then lots in
ascending id. Migration range `V50`.

## Files to create

- `loyalty/contexts/points/...` feature `expiry` (use case, port, persistence)
- `loyalty/apps/app-boot/.../jobs/PointExpiryJob.java`
- `loyalty/apps/app-boot/.../jobs/ExpiryNoticeJob.java`
- `loyalty/apps/app-boot/src/main/resources/db/migration/V50__expiry.sql`: the partial index on `(expires_at) where
  remaining > 0` if phase 11 did not create it, and `point_lots.expiry_noticed_at timestamptz null` with a partial index on
  `(expires_at) where remaining > 0 and expiry_noticed_at is null`
- Tests: `PointExpiryIntegrationTest`, `ExpiryRaceTest`, `ExpiryNoticeIntegrationTest`

## Tasks

1. **Finding work.** Select due members with `select distinct member_id from point_lots where remaining > 0 and expires_at <=
   now() order by expires_at limit :batch` per tenant sweep (RLS applies), no lock held.
2. **Per member.** In a short transaction, lock the member, re-read its due lots `for update` in ascending id, and for each lot
   still having `remaining > 0` write an `EXPIRE` entry (negative points, a `lot_allocations` row) and set `remaining` to 0.
   Anything already spent or expired by another flow is skipped silently.
3. **Schedule.** Hourly by default (a setting), using the lease and run-record tables copied in phase 4 so two instances never
   run the same tenant slice.
4. **Visibility.** `expiringSoon` in `GET /members/{ref}` (phase 12) already reads lots; add a test that it matches what the
   job later expires. The ledger lists `EXPIRE` entries.
5. **Debt.** Expiry never touches `debt`.

6. **Webhooks.** One `points.expired` per member per expiry transaction, summing the points and counting the lots it
   expired, with the balance after it; nothing when no lot was due.
7. **Expiry notice.** `ExpiryNoticeJob`, daily by default (a setting), with the same leases and tenant sweep. For a tenant
   whose `expiry_notice_days` is above 0, select members having lots with `remaining > 0`, `expiry_noticed_at is null` and
   `now() < expires_at <= now() + expiry_notice_days`. Per member, in a short transaction with the member lock first, re-read
   those lots `for update` in ascending id, set `expiry_noticed_at = now()` and write one `points.expiring` summing their
   `remaining`, with the earliest `expires_at`. A lot is announced at most once; one spent to 0 before the run is skipped; a
   lot that was announced and is then partly spent is not announced again; a lot whose `expires_at` has already passed is
   left to the expiry job and never announced. Raising `expiry_notice_days` announces lots that newly fall inside the window
   on the next run; lowering it changes nothing already announced. Erased members are skipped.

## Verification

- `cd loyalty && ./gradlew build` green.
- Tests prove: lots expire in `expires_at` order and spent lots are never expired; running the job twice, or two instances at
  once, writes each `EXPIRE` once; a redemption racing the job (100 iterations) never makes any `remaining` negative and
  `Σ remaining` equals the ledger sum; `available` is the same before and after the job runs; a member with only expired lots
  reports 0 and is not in debt; the notice job announces each lot once across repeated runs and two instances, combines a
  member's newly due lots into one `points.expiring`, sends nothing when `expiry_notice_days` is 0, and never announces a
  lot that is spent to 0 or already expired.

## Risks

- A tenant with millions of due lots after downtime: the batch is bounded and the partial index keeps each pass cheap; the job
  catches up over several passes and a metric exposes the oldest unexpired due lot.
