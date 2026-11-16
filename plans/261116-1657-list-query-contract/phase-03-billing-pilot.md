# Phase 3 — billing pilot

**Goal**: prove the contract on `/payments` and `/invoices`, end to end, before it is repeated.

**Done means**
- [x] Both lists use the contract (API and portal); tenant-system scoping and RLS still apply; the old parameters are
  gone. No related field is declared: payments and invoices belong to different contexts and ArchUnit table
  ownership forbids a cross-context relation, so the relation path is covered by unit tests here and first used on
  charges in phase 4.
- [x] Migration `V30__list_query_indexes.sql` adds the indexes the sortable fields need; a Testcontainers test asserts
  every sortable field has one and that a missing one is reported.
- [x] OpenAPI snapshot regenerated; compared semantically: 103 operations unchanged, four changed (invoices and
  payments, API and portal), three schemas added.
- [x] `BillingFeignApi` and `FeignBillingClient` read more than one page via cursor; client tests cover it.
- [x] Billing suite passes apart from the rule noted in the report (fixed afterwards); contract documented in
  `docs/list-query-contract.md`.
- [x] Contract reviewed by the owner before phase 4: snake_case filter names confirmed; response bodies stay camelCase.
