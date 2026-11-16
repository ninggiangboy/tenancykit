# Concurrency and locking

Every flow has a lock order. This document owns that order, the rules that keep coordination state out of business
transactions, and the safety net that turns a lock wait into a retryable answer instead of a hang or an opaque 500.
A new flow that changes a row another flow already locks must fit the order below.

## Observed order

The order is the order rows are locked, not the order endpoints are called.

| Flow | Locks in order |
|---|---|
| Subscription renewal | subscription, then wallet (through `money.charge`) |
| Charge, credit, debit, reserve, reverse | wallet first, then everything else. A charge's idempotency check runs after the wallet lock |
| Charge with coupons | wallet first, then the coupon rows that carry a budget, then the wallet's redemption rows ascending by id, all inside `claim` after the idempotency read |
| Redeem a coupon onto a wallet | wallet, then the coupon row, then the promotion code row, then the new redemption |
| Revoke a redemption | wallet, then the redemption row, read under its lock so the counters a charge just wrote are not written back stale |
| Deactivate a coupon and revoke its redemptions | coupon row, then every ACTIVE redemption of it ascending by id. A charge takes a budgeted coupon row before the redemptions for the same reason |
| Refund | payment, then refund, then wallet. The idempotency key is read after the payment lock |
| Reservation expiry | wallet with `skip locked`, then reservation (the comment on the statement says why) |
| Billing cycle close and `InvoiceChargeUseCase` | unbilled lines, then the invoice counter |
| Stripe event | event, then payment, then refund. A wallet credit goes through `PaymentSettlement`, so the wallet is after the payment. The retry job claims the event in an earlier, separate transaction, because the Stripe read happens before any row lock |
| Outbox relay | `skip locked`, so it never waits. Consumers share the relay's transaction but lock an invoice or unbilled line, never a wallet |
| Job lease, run record, rate-limit bucket, Stripe event claim | one coordination row in its own short transaction, never inside a business transaction |
| Platform: role rename and replace permissions | role `for no key update`, then each user holding the role, ascending by id, `for update` |
| Platform: role assign and unassign | role `for share`, then the user `for update`, then the assignment row is written |
| Platform: user patch, direct permission grant, `AccessPublisher` | the user `for update` |
| Platform: tenant rename and deactivate | tenant `for no key update` |
| Platform: tenant system update | tenant system `for no key update` |
| Platform: app entitlement enable and disable | entitlement row `for update`. When absent, `insert ... on conflict do nothing` then lock it |
| Billing: customer update and deactivate | customer `for no key update` |
| Billing: webhook endpoint patch and secret rotation | webhook endpoint `for no key update` |

Two consequences:

- No flow in the code today can form a wait cycle. The risk is a new flow that locks the wallet after, say, a
  reservation, or that holds a coordination row while calling a use case.
- The invoice counter is always the last lock. Invoice numbers are gap-free per Tenant, so issuing an invoice for a
  Tenant is serialised until commit. That is serialization, not deadlock, and it is only acceptable while the
  transaction is short: nothing runs after `InvoiceIssuer.issue`.

The coupon flows keep the same shape: every one of them locks the wallet first and reaches a coupon or redemption row
only while holding it, so **no flow locks a wallet after a coupon row** and the redeem flow (wallet, coupon, code,
redemption) and the charge flow (wallet, budgeted coupon rows, redemption rows) cannot wait on each other. Only a
coupon that carries a budget is a row shared across wallets, so a coupon bounded by an application cap alone adds no
cross-wallet lock edge. See [coupons.md](coupons.md).

## Rules

1. **A lease never holds a lock while the job runs.** Acquiring, renewing and releasing a lease is a short
   transaction on one `job_leases` row. Ownership is a deadline, not a held row lock, so there is no hold-and-wait.
   This is why a lease table is used instead of an advisory lock. If an advisory lock is ever unavoidable, only
   `pg_try_advisory_lock` is allowed; the blocking forms are forbidden.
2. **One statement touches one lease row.** A multi-row update has no defined lock order and can deadlock.
3. **A run record is claimed before the work, released when it fails and marked finished when it succeeds.**
   `job_runs` is inserted in its own short transaction; a conflicting claim inserts nothing and does no work. The
   claim is not inserted at the end of a long transaction. A failed attempt deletes the claim, so a later tick
   retries that key instead of waiting for the next day. A successful run sets `finished_at`, so it is never
   repeated. A claim left unfinished by a process that died is taken over once it is older than the job's TTL, so
   that Tenant does not lose its run for the day.
4. **The rate limiter runs in the authentication step, alone.** It is one function call that locks one bucket row in
   its own transaction. It never runs inside a business transaction: that would put a rate-limit row in front of the
   wallet and add a new lock edge to every money path. A request whose key does not authenticate spends one of a
   fixed 1024 buckets chosen by key id, so a caller inventing key ids cannot create unbounded rows or exhaust a real
   key's budget. A bucket is expired only once it has been idle long enough that an empty one would have refilled to
   the full burst; expired buckets are removed only by a worker, in small `skip locked` batches, until a batch comes
   back short.
5. **Bootstrap runs under the lease and holds nothing while calling Keycloak.** The seed steps commit on their own;
   the lease is ownership by deadline, not a row lock.
6. **The invoice counter is the last lock in every flow.** Do not add work after `InvoiceIssuer.issue`.
7. **Migrations use a short `lock_timeout` and retry.** `ALTER TABLE` asks for an exclusive lock, queues behind a
   long transaction, and then blocks every new query behind it. The `migrate` profile sets `lock_timeout` to 3
   seconds and retries a bounded number of times.
8. **Set a timeout per profile, not one number for everything.** The api may wait on a wallet row on purpose, because
   that is how two charges on the same wallet serialise. The worker should give up sooner and retry on its next tick.
   The `migrate` step should give up soonest. The running values are in `application-api.yml`,
   `application-worker.yml` and `application-migrate.yml`; they are starting points to measure under load.
9. **Treat `40P01` and `55P03` as retryable.** The api answers 503 with `Retry-After` and expects the caller to
   repeat the request with the same `Idempotency-Key`; every money operation has one. A job logs the round and
   retries on its next tick without counting an attempt: renewal leaves the period due, the Stripe event retry and
   the outbox relay leave the row due, and reconciliation leaves the item for the next run. A contended row is
   passed over for the rest of the tick, so it cannot starve the rows behind it. None of them turns a lock that
   simply timed out into a failure notice, a reconciliation incident or a dead letter.
10. **A background job walks tenants under one semaphore shared by every job.** `TenantSweep` runs one tenant per
    virtual thread, bounded by `billing.jobs.tenant-parallelism` permits that all jobs share; keep the permits below
    the worker's Hikari pool size so one job cannot starve the others of connections. The tenant scope is bound
    inside each task, because `ScopedValue` does not cross a thread boundary. Nothing inside a single tenant's money
    flow runs in parallel, and a permit is held for the length of the task, including an outbound HTTP call: that is
    what bounds the connections a stuck receiver can occupy.
11. **A write that bumps a state-transfer `version` holds the row lock across read, bump and write.** The row is
    locked first, then the version is read, bumped in memory and written under that lock, and the repository
    `update` carries `and version < :new` and throws when it updates no row. This matters because a consumer's
    replica keeps only a strictly newer version (`stored.version < excluded.version`): two events for one row at
    the same version with different state make the replica drop the second, and it then disagrees with the source
    until the next change. A user's version also moves when one of their roles changes permissions, so a version may
    advance without any visible field of that row changing. Platform's admin writes and Billing's customer and
    webhook-endpoint writes follow this rule.

## Conditional updates

Single-resource responses carry `ETag: "<version>"`, a strong tag holding the aggregate's version as a decimal.
An update may pin the version with `If-Match`; the rule is the same for every such endpoint:

- **`If-Match` is optional.** When present it is enforced; when absent, the write is last-write-wins as it always
  was. There is no 428 Precondition Required.
- **Comparison is strong, per RFC 9110.** `*` passes, a comma-separated list passes when any entry matches, a weak
  tag (`W/"3"`) never matches, and a malformed header is a 400.
- **A mismatch is 412, a business conflict is 409.** The 412 response carries the current `ETag` so a caller can
  retry without a separate GET.
- **The check runs after the row lock and before any mutation.** A no-op change — renaming to the same name, setting
  an entitlement to the state it already has — still checks the version first, so a stale tag is never answered with
  200. Visibility is checked before the lock where one applies, so a caller that cannot see a resource gets 404, not
  412.
- **A version may move without a visible field changing.** A spurious 412 is acceptable; a missed one is not.

`If-None-Match` and 304 are not implemented; GET responses return the `ETag` today so 304 can be added later without
changing the contract.

## Adding a flow

### Idempotency

Every keyed create follows one pattern, and a new flow picks one of its two shapes:

1. **Lock-first.** Lock the parent row every request with this key must lock, then read the key. This is valid only
   when the parent is part of the request hash, so a same-key request with another parent is by definition a
   different request. Used by charge, reservation, ledger postings and refund.
2. **Insert-first.** Insert with `on conflict (tenant_id, idempotency_key) do nothing`. When it inserts nothing, read
   the row that won. Used by usage, customer, top-up, invoice payment and subscription; `RecordUsageUseCase` is the
   reference implementation.
3. **Decide.** The same request hash returns the row's current state with `replayed = true`; a different hash is an
   `IdempotencyConflictException` (409). A key-constraint violation that escapes a lock-first flow is also an
   idempotency conflict, never a generic conflict or a `DataIntegrityViolationException`.
4. **Status.** 201 for a new resource and 200 for a replay. A recorded outcome such as the 402 of a failed charge is
   replayed unchanged. Mark the handler `@RepeatableCreate` so the API document lists both 201 and 200; without it a
   `ResponseEntity` handler is documented with a single 200.

The key and the request hash live on the row the request creates, guarded by `unique (tenant_id, idempotency_key)`;
there is no separate idempotency table. A repository names that constraint through the generated `Keys` constant, so a
renamed constraint fails the build instead of turning an idempotency conflict into a generic one.

Test a new keyed flow with `UncommittedRace` as well as a parallel burst: it holds the first request's transaction open
until the second is blocked on its row, so the path where the second request misses the uncommitted row is always
taken.

Before merging a flow that writes money, payments, invoices or coordination state:

1. Write down which rows it locks and in what order.
2. Check that the order is a subsequence of the table above; do not lock an earlier row after a later one.
3. Keep every coordination statement in its own short transaction and to one row.
4. Run the deadlock stress test (charge, renewal, reservation expiry, cycle close and Stripe settlement on one
   wallet) and confirm `pg_stat_database.deadlocks` stays at zero.

## Related

Money path and wallet ordering: [wallet-ledger-and-charging.md](wallet-ledger-and-charging.md). Invoice counter:
[invoicing-and-subscriptions.md](invoicing-and-subscriptions.md). Reliability requirements:
[security-and-reliability.md](security-and-reliability.md).
