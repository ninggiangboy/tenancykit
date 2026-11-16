# Phase 2 — core-spring bindings

**Goal**: compile a `ListQuery` to jOOQ and expose it to controllers and OpenAPI.

**Files**: `core/core-spring/.../query` (compiler, resolver, index check) and the new `core/core-openapi` module.

**Done means**
- [x] `JooqBinding` maps each schema field to a column or a one-hop `exists(subquery)`; a binding/schema mismatch
  fails fast (test helper every app reuses).
- [x] `JooqListQuery` builds condition, order, keyset or offset window, fetches `limit + 1`, returns `Page` with
  `next_cursor` and an optional `count(*)` total. SQL is verified by rendering.
- [x] A `HandlerMethodArgumentResolver` supplies `ListQuery` to controllers for a schema named by an annotation.
- [x] A springdoc customizer generates `filter[...]`, `sort`, `limit`, `offset`, `cursor`, `page_info` parameters and
  the shared `Page`/error schemas from the schema.
- [x] `/v3/api-docs` exposure is confirmed in billing.
