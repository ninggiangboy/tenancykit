# Phase 4 — billing rest

**Done means**
- [x] customers, subscriptions, usage events, coupons and redemptions, webhook deliveries, stripe events,
  incidents, wallets, ledger (with time range), plans, portal lists all use the contract. The usage **aggregates**
  endpoint stays a report with its required `customerId`/`from`/`to` window, because it returns one total per metric
  rather than rows of a resource; `docs/list-query-contract.md` records that exception.
- [x] New `GET /charges`, `/reservations`, `/refunds` filter by `reference` and related ids.
- [x] Grep finds no old `before`/`status`/`customerId`/`afterSequence` list parameter and no unbounded list.
- [x] Billing and tenant suites pass.
