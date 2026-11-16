---
phase: 11
title: "Human tasks"
status: pending
priority: P1
effort: "L"
dependencies: [10]
---

# Phase 11: Human Tasks

## Goal

A workflow can stop at a task assigned to a user or a task group, with a due time, an escalation and a decision, and an authorised
caller completes it through the API.

## Context

API only: there is a task inbox API, no notification (a workflow calls mailroom itself, per the confirmed non-goals). Timers
from phase 8 drive due and escalation.

**Verified while planning:** Platform's replica holds each user's *effective permission codes* (`user-access.changed`: one
row per user, "consumers never join roles themselves"), not role membership. So a task cannot be assigned to a Platform role.
Decision (plan resolution 8): a task is assigned to a **user id** or to a **task group**, a named list of user ids that
workflow owns. Re-confirm the replica shape in the first task of this phase (`git grep -n -i "user_access" -- core
workflow/contexts/platform`); if it has changed and now carries roles, say so and ask before switching.

## Files to create

- Migration `V8__tasks.sql`: `task_group` (tenant, code, name), `task_group_member` (group, user id), `task` (instance, token,
  title, assignee user id or group code, form schema, status
  `OPEN|CLAIMED|COMPLETED|CANCELLED|ESCALATED|EXPIRED`, due at, escalated to, decision, output, completed by, completed via
  `USER|SYSTEM`, version), RLS on all three
- `workflow/contexts/tasks`: domain, application (`CreateTask`, `ClaimTask`, `CompleteTask`, `ReassignTask`), adapter
  (controller, jOOQ)
- `runtime`: `TaskExecutor` (creates the task and parks the token as WAITING)
- OpenAPI: `GET /api/v1/tasks` (core-query contract, mine and by group), `GET /api/v1/tasks/{id}`, `POST .../claim`,
  `POST .../complete`, `POST .../reassign` (`workflow:task:read|complete`), and task groups: `GET|POST /api/v1/task-groups`,
  `PUT|DELETE /api/v1/task-groups/{code}` (`workflow:task:manage`, a tenth code added to `contracts/permissions/workflow.yaml`)
- Tests

## Tasks

1. `task` node: title and form as expressions, `assignee` (user id) or `group` (code), `due` (duration), `escalate` (a group
   code and optional second due), `outcomes` (the allowed decisions, at least one). Publish checks that a named group exists;
   a group deleted later makes new tasks fail with a clear reason, and open tasks keep their snapshot of members.
2. Task groups: members are user ids that must be ACTIVE users in the replica when added; a member who is later disabled loses
   access at once (checked on every read and completion against the replica).
3. Visibility and completion (plan resolution 5): a user principal sees tasks assigned to them or to a group they belong to
   and may complete them, provided the user holds `workflow:task:complete` (from the replicated effective permissions); a
   Tenant System principal with `workflow:task:complete` may complete any task of its Tenant and must name the person
   (`completedBy`), recorded as stated by the system and marked `SYSTEM`.
4. Completion is one conditional update `OPEN|CLAIMED -> COMPLETED` with `If-Match`; the decision must be one of `outcomes`, the
   output must validate against the form schema (a small JSON Schema subset: types, required, enum, bounds); on success the
   token resumes carrying decision and output. A second completion answers 409.
5. Due and escalation use `TASK_DUE` timers: at due, escalate once to the escalation group (status `ESCALATED`, new due), then at
   the second due the task takes the node's `on_expire` branch (`expired`, reject, or fail).
6. Task events (created, assigned, escalated, completed, expired) go to the outbox for phase 13.
7. Cancelling an instance cancels its open tasks.

## Validation

- Tests: complete once, second attempt 409; wrong decision value 422; user outside the assignee and group cannot read or complete (404, not 403,
  to avoid leaking existence); escalation fires once; expiry takes `on_expire`; RLS.
- The refund-approval sample (phase 15) runs with a group task, an escalation and an approval.

## Risks

- Mixed principals (user versus Tenant System) are easy to get wrong: one authorisation function with a table-driven test over
  every principal and task state.
