---
phase: 14
title: "SLA and business hours"
status: pending
priority: P1
effort: "L"
dependencies: [5, 9]
---

# Phase 14: SLA and Business Hours

## Goal

A ticket carries a first-response deadline and a resolution deadline computed in business hours; the timers pause while the
ticket waits for the customer, and `sla.at-risk` and `sla.breached` fire exactly once per timer, even across restarts.

## Context

The timer engine is helpdesk's own: rows with `due_at`, swept with `FOR UPDATE SKIP LOCKED` like the other apps' jobs. It does
not call workflow or any suite app. Business-hours arithmetic is the correctness risk (DST, holidays, windows across midnight),
so it is a pure module with no database, tested against a reference implementation written in the test code.

## Files to create

- Migration `V10__sla.sql`: `business_hours` (tenant, IANA zone), `business_windows` (weekday, start, end), `holidays` (date),
  `sla_policies` (tenant, name, per-priority first-response and resolution minutes, `at_risk_fraction`), `queues.sla_policy_id`,
  `sla_timers` (ticket, kind FIRST_RESPONSE|RESOLUTION, `started_at`, `due_at`, `remaining_business_minutes`, `paused_at`,
  `at_risk_at`, `at_risk_fired_at`, `breached_fired_at`, `met_at`), RLS, jOOQ regenerated
- `helpdesk/contexts/sla/src/{domain,application,api,adapter}/...`: `BusinessCalendar` (pure), `SlaPolicy`, `SlaTimer`,
  `StartTimersOnTicketCreated`, `PauseResumeOnStatus`, `MeetOnFirstAgentReply`, `SlaSweepJob`, controllers
  (`helpdesk:sla:manage`)
- Tests: `BusinessCalendarPropertyTest` (random windows, zones, DST edges, holidays versus the reference), `SlaTimerTest`,
  `SlaSweepConcurrencyTest`, `SlaPauseResumeIntegrationTest`, `SlaRowLevelSecurityTest`

## Tasks

1. **Calendar.** `addBusinessMinutes(start, minutes)` and `businessMinutesBetween(a, b)` over weekly windows in an IANA zone with
   holidays; windows may cross midnight; a start outside hours begins at the next window; DST jumps neither add nor lose
   minutes inside a window.
2. **Policies.** `/sla-policies` and `/business-hours` CRUD; a queue points at a policy; a ticket takes the policy of its queue
   and its priority at creation, and a priority change recomputes the open timers from `now` for the remaining share.
3. **Timers.** Creation starts both timers. The first-response timer is met by the first public agent reply
   (not a note, not an automatic message). The resolution timer is met when the ticket becomes `SOLVED`; reopening restarts it
   with the remaining business minutes it had.
4. **Pause.** `PENDING` pauses both timers (stores the remaining business minutes); a customer reply resumes them. A paused
   timer never fires.
5. **Fire.** The sweep claims due timers with `SKIP LOCKED`; firing sets `at_risk_fired_at` or `breached_fired_at` with a
   compare-and-set and writes the outbox event in the same transaction, so a crash between the two cannot lose or repeat it.
   `at_risk_at` is `started + fraction × budget` in business time.
6. **Visibility.** `GET /tickets/{id}` includes each timer's state and `dueAt`; `GET /tickets` filters `slaState=BREACHED|AT_RISK`.

## Verification

- The property test finds no mismatch with the reference over 10,000 random cases including both DST transitions of two zones.
- Two concurrent sweeps fire a due timer once; killing the worker between claim and commit and restarting fires it once.
- A ticket paused for three days breaches at the time computed from the remaining minutes, not from creation.
- Changing priority on an open ticket updates `dueAt` and keeps already-fired flags.
- `sla.at-risk` and `sla.breached` payloads validate against their schemas.

## Risks

- Policy edits do not rewrite existing timers; the response states this, and a priority change is the only recompute.
