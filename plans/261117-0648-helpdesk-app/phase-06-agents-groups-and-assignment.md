---
phase: 6
title: "Agents, groups and assignment"
status: pending
priority: P1
effort: "M"
dependencies: [5]
---

# Phase 6: Agents, Groups and Assignment

## Goal

A ticket can be assigned to an agent or to a group, a queue can assign new tickets automatically in round-robin order, and an
assignment to someone who may not work tickets is refused.

## Context

Agents are Platform users; the replica table `user_grants` holds their effective permissions per user, not role membership.
Helpdesk therefore owns its own agent groups: a group is a named list of user ids, managed through the API
(`helpdesk:group:manage`), the same decision workflow made for its task groups. A user may be added only while the replica shows
a helpdesk permission for them, and is re-checked at assignment time because a grant can be revoked later.

## Files to create

- Migration `V3__agents.sql`: `agent_groups`, `agent_group_members`, `queue_assignment_cursor` (per queue, last assigned
  member), `tickets.assignee_user_id`, `tickets.assignee_group_id`, RLS, jOOQ regenerated
- `helpdesk/contexts/ticketing/.../agent/**`: `AgentDirectory` port reading `user_grants`, `AssignTicketUseCase`,
  `RoundRobinAssigner`, group use cases and controller
- Tests: `AssignmentEligibilityTest`, `RoundRobinConcurrencyTest`, `GroupMembershipTest`, `AssignmentAuditTest`

## Tasks

1. **Eligibility.** `AgentDirectory.canWork(tenant, user)` is true when the user's grants include `helpdesk:ticket:reply`.
   An assignment, a group member add and a round-robin pick all call it; a failing one answers `422 agent_not_eligible`.
2. **Manual assignment.** `POST /tickets/{id}/assign` takes `userId`, `groupId` or both (a group with a named owner), or null to
   unassign; it writes a `ticket_events` row and emits the domain event that phase 9 turns into `ticket.assigned`.
3. **Round-robin.** A queue may set `autoAssign: ROUND_ROBIN` with a group; a new ticket picks the next eligible member after
   the cursor, in one transaction with `FOR UPDATE` on the cursor row so two workers cannot pick the same member twice in a row;
   if no member is eligible the ticket stays unassigned in the queue and the reason is recorded.
4. **Revocation sweep.** A daily worker job lists open tickets assigned to a user who is no longer eligible and unassigns them
   with an event, so no ticket stays with someone who lost access.
5. **Reads.** `GET /groups`, `GET /groups/{id}/members`, filter tickets by assignee user or group.

## Verification

- Assigning to a user without `helpdesk:ticket:reply` is `422`; to a user of another Tenant is `404`.
- 50 concurrent creations into a queue with three eligible members distribute 17/17/16 (within one) with no duplicate pick.
- Revoking a user's grant in the replica makes the sweep unassign their open tickets and write the event once.
- Every assignment change has exactly one `ticket_events` row and one outbox row.

## Risks

- The replica can lag a revocation; the daily sweep and the check at assignment bound the exposure, and `docs/helpdesk.md`
  states that replica lag is the limit.
