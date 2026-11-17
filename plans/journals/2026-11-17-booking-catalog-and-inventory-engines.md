---
title: Booking catalog and inventory engines
date: 2026-11-17
summary: "Phases 4-7: catalog CRUD, recurrence, sessions, interval and capacity engines"
---

# Booking catalog and inventory engines

Implemented booking phases 4-7 in one run.

Catalog: resources with a zone, weekly minutes and dated exceptions; groups,
offerings, requirements and cancellation windows, over a real-token MockMvc
harness and an OpenAPI contract test. Recurrence is lib-recur 0.17.1 behind
`RecurrenceExpander`; every committed vector passes through the adapter and
the preview endpoint, and each occurrence is resolved with the JDK's own gap
and overlap rules. Sessions materialize idempotently inside the offering
horizon, on create, on change and from the worker.

Inventory: the interval engine claims a half-open range and lets a PostgreSQL
exclusion constraint refuse overlap; availability is computed per request in
the resource's zone. The capacity engine claims places with `SKIP LOCKED`, so
a pool never oversells, and materializing a session creates its places in one
statement. Both are wired through `catalog.api`/`inventory.api` named
interfaces.

Evidence: `cd booking && ./gradlew check` green after every phase; 1000
parallel claims over 100 places hold exactly 100; 50 overlapping interval
claims leave one; all RLS cases pass; commits per phase on `dev`.

Next: phase 8 (the Booking aggregate and its lifecycle).

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
