---
phase: 1
title: "Scope, docs, contracts and decisions"
status: pending
priority: P1
effort: "M"
dependencies: []
---

# Phase 1: Scope, Docs, Contracts and Decisions

## Goal

The booking app's scope, vocabulary, HTTP contract, permission catalog, ten webhook schemas and recurrence test vectors exist
as documents before any code, and the suite docs say that a second business-neutral tenant-facing app now owns "time and
places".

## Context

Executable owners take over from docs once code exists (`docs/README.md`, Authority). Contracts are documents shared across
builds, never code (`contracts/README.md`). A permission file is `<app>:<resource>:<action>` with `scopes` and
`grantable_to_tenant_system` (`contracts/permissions/survey.yaml`). The Platform build packages
`contracts/permissions/*.yaml` into its app catalog (`platform/apps/app-boot/build.gradle.kts`), so a file here is what makes
`booking` an entitlement `app_code`. An event without a schema is never published
(`contracts/messaging/README.md`, Envelope).

## Files to create

- `docs/booking.md`: purpose, vocabulary, the two engines and their promises, the `Booking` state machine, lock order,
  confirm-after-payment rule, time and recurrence rules, waitlist, the public gate, the Tenant recipe (hold, charge through
  Billing, confirm), the settings table and non-goals
- `contracts/openapi/booking.yaml` (skeleton generated later by the build; here the intended paths and schemas)
- `contracts/permissions/booking.yaml`
- `contracts/events/booking/{booking.held,booking.confirmed,booking.rescheduled,booking.cancelled,booking.expired,booking.no-show,waitlist.offered,waitlist.lapsed,series.created,series.cancelled}.schema.json`
- `contracts/booking/README.md` and `contracts/booking/recurrence-vectors/{weekly,monthly-setpos,exdate-rdate,dst-new-york,dst-london,dst-lord-howe,no-dst-ho-chi-minh,session-series}.json`
- Edit: `docs/README.md` (table row), `docs/overview-and-scope.md` (scope paragraph and a dated reversal of the "second business
  app" non-goal), `docs/suite-messaging.md` (booking declares no exchange; queue `booking.platform-events`),
  `contracts/README.md`, `contracts/events/README.md`, `contracts/messaging/README.md`

## Tasks

1. **Confirm the header-set approach.** Read how `crm/contexts/webhook` builds its signed headers
   (`git grep -n "Event-Id" -- crm/contexts/webhook`); record whether it is an app-local set. Booking follows it. Core is not
   edited.
2. **Permissions.** Codes: `booking:resource:{read,manage}`, `booking:offering:{read,manage}`, `booking:calendar:{read,manage}`,
   `booking:booking:{read,hold,confirm,manage}`, `booking:series:{read,manage}`, `booking:waitlist:{read,manage}`,
   `booking:webhook:{read,manage}`, `booking:token:issue`, `booking:settings:{read,manage}`. All grantable to a Tenant
   System except `booking:settings:manage`. Mirror survey's file shape.
3. **Webhook schemas.** Each payload carries the booking's full state, `bookingVersion`, `customerRef`, `offeringId`, the
   claimed range or `sessionId`, the assigned resources, `source` (`TENANT`, `GATE`, `WAITLIST`) and, where it applies,
   `policyCode`; follow `contracts/events/survey/*.schema.json` for envelope fields.
4. **Recurrence vectors.** One file per theme. A vector is `{name, zone, dtstart (local), rule (iCalendar lines), from, to,
   expect: [UTC instants]}`. Required cases: weekly with BYDAY, monthly nth weekday and BYSETPOS=-1, EXDATE and RDATE, UNTIL
   and COUNT, a start inside a spring-forward gap, a start inside a fall-back overlap, a 30-minute DST zone, and a zone with
   no DST. Expected values are computed by hand from the zone rules and reviewed, not copied from the library.
5. **OpenAPI intent.** Prefix `/booking/api/v1`, public gate under `/booking/p/**` (also `/p/**`). Typed problems: `409
   slot-taken`, `409 sold-out`, `409 hold-lapsed`, `422 outside-open-hours`, `422 invalid-recurrence`. Every list endpoint
   uses the shared list query contract (`docs/list-query-contract.md`).
6. **Suite docs.** State the rule once: booking never sees money, a Tenant confirms after paying through Billing, and no suite
   app binds to booking.

## Verification

- JSON files parse; every schema validates its own example; `contracts/permissions/booking.yaml` loads with the same checker the
  Platform build uses.
- `git grep -n -i "future app" -- docs/suite-messaging.md contracts/messaging/README.md` finds no sentence that contradicts the
  no-app-links rule.
- The recurrence vectors are reviewed against the RFC 5545 text for the DST cases before phase 5 uses them.

## Risks

- Hand-computed vector expectations can be wrong; a second reading of each DST case is part of this phase, and phase 5 treats a
  disagreement as a question for the vector first and the library second.
