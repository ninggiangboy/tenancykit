---
phase: 4
title: "Catalog: resources, groups, offerings and calendars"
status: completed
priority: P1
effort: "L"
dependencies: [3]
---

# Phase 4: Catalog: Resources, Groups, Offerings and Calendars

## Goal

A Tenant System describes what can be booked and when it is open: resources with a time zone and weekly hours, groups of
resources, offerings of either kind with their rules, and exceptions to the weekly hours. No booking exists yet.

## Context

All writes use ETag and `If-Match` (`core/core-kernel/.../version`), natural-key creates use `RepeatableCreate`, lists use the
shared list query contract (`docs/list-query-contract.md`, `core/core-query`). Codes are chosen by the Tenant, unique per Tenant,
immutable. Recurrence blocks and capacity sessions come in phase 5; this phase stores only the weekly pattern and dated
exceptions. Migration range: next free versions after `V1`.

## Files to create

- Migrations `V2__catalog.sql` (resources, working hours, exceptions) and `V3__offerings.sql` (groups, members, offerings,
  requirements, cancellation windows); regenerate and commit `booking/libs/jooq-schema`
- `booking/contexts/catalog/src/{domain,application,api,adapter}/...` for features `resource`, `group`, `offering`, `calendar`
- Tests: `ResourceApiIntegrationTest`, `OfferingApiIntegrationTest`, `CalendarRulesTest`, `CatalogIsolationTest`

## Tasks

1. **Resource.** `resources(tenant_id, id, code, name, zone, seats null, status ACTIVE|ARCHIVED, version)`. `zone` is a valid IANA id,
   checked against the JDK's tz database, immutable once any booking exists. `seats` is for table fitting (phase 9).
2. **Weekly hours.** `working_hours(resource_id, weekday 1-7, opens local time, closes local time)`, several rows per weekday,
   no overlap per weekday, `closes` may be `24:00`. Stored as local times; interpreted in the resource's zone.
3. **Exceptions.** `calendar_exceptions(resource_id, local date range, kind CLOSED | OPEN, hours)`; `OPEN` replaces that day's
   hours. An exception on a past date is allowed.
4. **Groups.** `resource_groups(code, name, strategy PRIORITY|ROUND_ROBIN|LEAST_BUSY|FIT_SEATS, max_combine default 1,
   cursor)` and `group_members(group_id, resource_id, priority)`. A resource may belong to many groups.
5. **Offering.** `offerings(code, name, kind INTERVAL|CAPACITY, status DRAFT|ACTIVE|ARCHIVED, duration_min, duration_max,
   duration_step, slot_step, buffer_before, buffer_after, lead_time, horizon_days, hold_ttl_seconds null, max_qty,
   public_shows_remaining)`. `INTERVAL` needs requirements; `CAPACITY` needs none (its pools come with sessions in phase 5).
6. **Requirements.** `offering_requirements(offering_id, slot_code, resource_id xor group_id)`; one booking claims one resource
   per requirement, all distinct. Validate that a resource is not named twice and every referenced resource is active.
7. **Cancellation windows.** `cancellation_windows(offering_id, minutes_before_start, code)` ordered by `minutes_before_start`
   descending; a tenant-chosen code, no amounts.
8. **Permissions.** `booking:resource:*`, `booking:offering:*`, `booking:calendar:*`; reads for the lists.
9. **Archive, never delete** a resource or offering that a booking references; deleting an unreferenced one is allowed.

## Verification

- `cd booking && ./gradlew build` green; `OpenApiContractTest` passes with the new operations.
- Tests prove: two tenants may share a code; overlapping hours for one weekday are `422`; an invalid zone is `422`; a stale
  `If-Match` is `412`; a requirement naming one resource twice is `422`; list endpoints honour filter, sort and paging; tenant B
  gets `404` for tenant A's resource.

## Risks

- Local times near DST are resolved in phase 5, not here; the model stores local values on purpose so a zone-rule change does not
  need a migration.
