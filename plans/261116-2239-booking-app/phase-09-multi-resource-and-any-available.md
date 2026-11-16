---
phase: 9
title: "Multi-resource claims and any-available assignment"
status: pending
priority: P1
effort: "L"
dependencies: [8]
---

# Phase 9: Multi-Resource Claims and Any-Available Assignment

## Goal

One booking claims several resources at once, all or nothing (staff plus room, a service plus its equipment), and a requirement
naming a group is satisfied by whichever member the group's strategy picks, including fitting a party to one or several tables.

## Context

Requirements were stored in phase 4 (`offering_requirements`). A claim touches several resources, so the lock order matters:
booking row, then resources by ascending id. PostgreSQL detects deadlocks, but a fixed order avoids them and the tests prove it.
Strategies are `PRIORITY`, `ROUND_ROBIN`, `LEAST_BUSY` and `FIT_SEATS`.

## Files to create

- Migration `V8__group_strategy_state.sql` if the round-robin cursor or busy counters need columns; regenerate jOOQ
- `booking/contexts/inventory/.../assignment/**`: `ResourceSelector` with one class per strategy, candidate ordering, combine search
- `booking/contexts/booking/...`: wire multi-requirement holds in the create use case
- Tests: `MultiResourceClaimTest`, `AssignmentStrategyTest`, `FitSeatsTest`, `CrossingResourceSetsDeadlockTest`

## Tasks

1. **Candidate ordering per requirement.** `PRIORITY`: ascending member priority. `ROUND_ROBIN`: start after the group's last assigned
   member, advance the cursor inside the booking transaction. `LEAST_BUSY`: fewest booked minutes on the local day of the start, ties by
   priority. `FIT_SEATS`: given `partySize`, the single member with the fewest sufficient `seats`; if none, the smallest-waste
   combination of up to `max_combine` members (bounded search, groups capped at 20 members), claiming all of them.
2. **Try candidates in order.** Each attempt runs under a savepoint; on `slot-taken` try the next; if none succeed, `409 slot-taken`
   with the requirement that failed.
3. **Distinct resources.** One booking never uses the same resource for two requirements; remove a chosen resource from later
   requirements' candidates.
4. **Atomicity.** Every requirement's claim is in the booking's transaction; any failure rolls back all claims and the booking row.
5. **Lock order.** Sort every claim by resource id before inserting; the interval engine inserts in that order regardless of which
   requirement the resource satisfies.
6. **Availability for several requirements** (extends phase 6): a start is free when each requirement has at least one free
   candidate and the distinctness rule can be met; `GET /offerings/{id}/availability` accepts `partySize` for `FIT_SEATS`.
7. **Assignment is returned**: the booking read model lists `{slotCode, resourceId}` for each claimed resource.

## Verification

- Staff plus room: with the room taken, nothing is claimed for the staff either; with both free, both are claimed.
- 200 parallel bookings over crossing resource sets (A+B and B+A requirements) finish without a deadlock error escaping to the
  caller; every outcome is success or `slot-taken`.
- Each strategy picks as defined on a fixed data set (including a tie and a unavailable member); round robin rotates across calls.
- `FIT_SEATS` picks a 4-seat table for 3 people, combines two 2-seat tables for 4 when no 4-seat exists, and refuses beyond
  `max_combine`.

## Risks

- Combination search is combinatorial: the 20-member cap and `max_combine` bound it; document that larger floors should split into
  groups.
