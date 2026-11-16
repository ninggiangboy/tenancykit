---
title: Review of the list-query contract and the fixes it found
date: 2026-11-16
summary: Cursor parsed stored values through request rules; OpenAPI hid absent paging facts; respondents lost externalRef
---

# Review of the list-query contract and the fixes it found

## Context

The previous session assessed the filters for the Tenant System, designed and
landed the shared list-query contract (`d72596b5..7c3129c1`), then started a
read-only review and hit the usage limit. This session finished that review,
then fixed what it found and committed (`9510c776`).

## Findings and repairs

1. **A cursor could not carry a long string.** `Cursor.decode` reused
   `FieldType.parse`, which enforces the 255-character request limit. Mailroom
   sorts `receivers.email` and `suppressions.email_normalized`, both of which the
   database allows to reach 320 characters, so a page ending on such a row
   answered `the cursor is not valid` for the next page. `FieldType.parseStored`
   now parses a cursor's value with the stored rules; a request value keeps the
   limit. `QueryParserTest.aCursorRoundTripsAStringLongerThanARequestValueMayBe`
   covers it.

2. **The OpenAPI hid the absent paging facts.** The contract makes `page` null
   unless `page_info=true`, `offset` null when a cursor positioned the page and
   `next_cursor` null on the last page, while the generated schema typed all three
   as always present. `ListingOpenApiCustomizer` now also implements springdoc's
   `OpenApiCustomizer` and marks them absentable in the version's own form: a
   null alternative in 3.1 (`oneOf` for a `$ref`, `type: [x, "null"]` for a
   value), the `nullable` keyword in 3.0. Each app's bean names the concrete
   class so springdoc resolves it for both interfaces, and all six documents were
   regenerated and committed.

3. **The respondents listing always returned `externalRef: null`.** The resend
   recipe reads that field to join a mailroom group, and the reader never
   selected it. `JooqResponseReader` now fetches the identity and the reference in
   the one per-page query it already ran, and
   `RespondentListingIntegrationTest` submits a personal link and reads the row
   back.

4. **Dead code.** `FieldType.parse` had a `BadRequestException` branch inside a
   catch that cannot see it (`RuntimeException` is not caught); removed.

## Deliberately not changed

`page_info=true` also runs the `count(*)` for `total`, so a client walking a
large listing with the cursor pays one count per page. The tenant client asks for
page facts on every page because `next_cursor` is only returned with them. Making
`next_cursor` independent of `total` is a contract change with API surface of its
own; it stays a recorded trade-off rather than a silent redesign.

## Also in the tree

The survey docs' respondents example used the removed `state=` parameter and
claimed `externalRef`; the previous sentence and the visit-state explanation were
swept into another session's contracts commit (`39b01892`) before this commit was
made, so this commit carries the code and contracts only.

## Verification

- `core`: full `./gradlew test` (all modules, including the ArchUnit gates).
- survey: `Architecture*`, `OpenApiContractTest`, `ListingIndexIntegrationTest`
  and the new `RespondentListingIntegrationTest` over the real context and
  PostgreSQL container.
- billing, mailroom, survey, notification, platform, attribution:
  `-Dopenapi.update=true` regenerated each document and the same run asserted the
  committed file equals it.

Full app suites other than survey's targeted subset were not re-run in this
session.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
