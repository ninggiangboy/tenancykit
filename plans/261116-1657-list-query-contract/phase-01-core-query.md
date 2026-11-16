# Phase 1 — core-query

**Goal**: the pure-Java contract: `FilterSchema`/`FieldSpec`, `QueryParser`, validation with limits, cursor codec,
`ListQuery`, `Page`/`PageInfo`.

**Files**: new module `core/core-query` (registered in `core/settings.gradle.kts`, boundary test like core-kernel).

**Done means**
- [x] A schema rejects a sortable nullable field, a sortable relation, `contains` on a non-text field and an enum
  without values.
- [x] The parser turns `filter[...]`, `sort`, `limit`, `offset`, `cursor`, `page_info` into a `ListQuery` and answers
  every malformed or over-limit input with `BadRequestException` naming field, operator and reason.
- [x] A cursor round-trips, and is refused with another filter or sort or with a tampered/garbled body.
- [x] Unit tests cover each rule above; `./gradlew :core-query:test` passes.
