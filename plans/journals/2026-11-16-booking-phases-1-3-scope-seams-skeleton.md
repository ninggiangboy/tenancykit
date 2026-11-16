# Booking: phases 1–3 (scope, seams, skeleton)

Date: 2026-11-16. Plan: `plans/261116-2239-booking-app/`.

## What landed

- **Phase 1** — `docs/booking.md`, `contracts/openapi/booking.yaml` (50 paths, the public gate included),
  `contracts/permissions/booking.yaml` (19 codes), ten webhook schemas each carrying a validating example,
  `contracts/booking/recurrence-vectors/` (twelve vectors plus a schema), and the suite docs now count the app.
- **Phase 2** — `booking_owner`/`booking_app` roles and the `booking` database, the `booking` broker user with
  `read` on `platform.events` and no exchange, the `/booking/*` Caddy route, the Keycloak audience, the Platform
  service client, the three compose runtimes, the CI workflow and the isolation checks.
- **Phase 3** — `booking/` as its own Gradle build: five contexts (empty), the Platform replica, security, the
  tenant sweep, the settings record, `V1__baseline.sql` with RLS, and a jOOQ schema generated from the migrations
  and checked by a drift test. `cd booking && ./gradlew check` is green.

## Decisions and deviations

- **Header set (phase 1, task 1).** crm builds an app-local `WebhookHeaderSet` (`CrmWebhookHeaders.CRM`); booking
  will do the same with `Booking-*`. Core is not edited.
- **Messaging topology.** The plan said to copy survey's skeleton, but survey produces `survey.events`. Booking
  declares no exchange, so its `MessagingConfiguration` follows crm's consumer-only shape instead. No
  `BrokerRelay`, no export routes; the outbox exists for the webhook relay that arrives in phase 11.
- **Recurrence vectors.** Expectations were computed from the tz database by hand (offsets printed and recorded in
  each `note`), not from any RRULE library. Twelve vectors cover weekly BYDAY, nth weekday, BYSETPOS=-1,
  EXDATE/RDATE, UNTIL/COUNT, a gap, an overlap, New York, London, the 30-minute Lord Howe shift, Ho Chi Minh City
  and a Berlin session series.
- **jOOQ bootstrap.** `libs/jooq-schema` is generated from `V1` by a temporary `tools/jooq-gen` module (removed
  after one run). From here the committed `JooqSchemaTest` owns regeneration with `-Djooq.update=true`.
- **Architecture rules.** `ArchitectureRules` is adapted to booking's five contexts. Because the contexts hold no
  classes yet, `layer-direction` and `query-side-uses-no-domain-aggregates` are in `RULES_WITHOUT_SUBJECTS`; each
  is re-asserted from the phase that creates its first subject. `ArchitectureMetaTest` still proves every rule
  fails on its fixture, so no rule is toothless.
- **Permission catalog test.** The endpoint↔catalog assertions are deferred: with no controllers they would be
  vacuous or false. The constants↔catalog assertion runs now.

## Verification run

`cd booking && ./gradlew check` — BUILD SUCCESSFUL. Includes ArchUnit, Spring Modulith, the jOOQ drift test, the
row-level-security test (as `booking_app` against a real PostgreSQL), the api/worker startup tests and the
isolation gate.

Not run in this pass: `docker compose up -d --build --wait` for the booking services, and the two runtime identity
checks (tenant A token against tenant B's host, entitlement-less `403`). They need a full stack build and belong
with phase 4's first endpoint.

## Next

Phase 4 (catalog) is the first Java feature phase. The recurrence library spike is phase 5; phase 1's vectors are
its acceptance set.
