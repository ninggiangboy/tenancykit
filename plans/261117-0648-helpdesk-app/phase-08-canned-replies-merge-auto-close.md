---
phase: 8
title: "Canned replies, merge, auto-close and reopen"
status: pending
priority: P1
effort: "L"
dependencies: [6, 7]
---

# Phase 8: Canned Replies, Merge, Auto-Close and Reopen

## Goal

Agents answer faster with templates, collapse duplicates into one ticket, and stale solved tickets close themselves; a customer
reply brings a solved ticket back and never edits a closed one.

## Files to create

- Migration `V5__canned_merge_close.sql`: `canned_replies` (tenant, name, locale, subject, body, `scope` TENANT|QUEUE, queue),
  `tickets.merged_into`, `tickets.follow_up_of`, `tickets.solved_at`, `tickets.closed_at`, settings column
  `auto_close_days`, RLS, jOOQ regenerated
- `helpdesk/contexts/ticketing/.../canned/**`: `CannedReplyRenderer`, use cases, controller (`helpdesk:canned:manage`)
- `helpdesk/contexts/ticketing/.../merge/**`: `MergeTicketsUseCase`
- `helpdesk/contexts/ticketing/.../lifecycle/**`: `AutoCloseJob` (worker), `ReplyIntakePolicy`
- Tests: `CannedReplyRenderTest`, `MergeTicketsIntegrationTest`, `AutoCloseJobTest`, `ReplyIntakePolicyTest`,
  `MergeConcurrencyTest`

## Tasks

1. **Canned replies.** Plain-text templates with `{{requester.name}}`, `{{ticket.number}}`, `{{agent.name}}`, `{{queue.name}}`;
   an unknown variable is rejected when the template is saved (strict, like mailroom), a missing value renders empty only for
   `requester.name`. `POST /tickets/{id}/messages` accepts `cannedReplyId` to prefill; the agent's text always wins.
2. **Merge.** `POST /tickets/{id}/merge` with `into` moves all messages and attachments to the target in time order, keeps the
   source's requester as an extra participant on the target, closes the source with `merged_into`, records an event on both,
   emits the domain event for `ticket.merged`. Refuse a merge across Tenants, onto itself, onto a closed ticket, or into a
   ticket that is itself merged (`409`). Both ticket rows are locked in id order to avoid deadlock.
3. **Auto-close.** A worker job closes tickets in `SOLVED` for more than `auto_close_days` (setting, default 7), per Tenant in a
   bounded sweep; it writes an event and the `ticket.closed` domain event once.
4. **Reply intake policy.** One policy used by every channel: a customer message on `OPEN`, `NEW` adds to the ticket; on `PENDING`
   or `SOLVED` it reopens; on `CLOSED` or a merged source it creates a new ticket with `follow_up_of` pointing at it (for a
   merged source, at the target).
5. **Participants.** A ticket may have extra participants (copy recipients on a reply); keep it to a plain list of emails used by
   the mail context in phase 12, with a cap of 20.

## Verification

- Merge of two tickets with attachments leaves the source with zero messages, the target with all, and no orphaned object.
- Two concurrent merges in opposite directions (A into B, B into A) end with exactly one success and no deadlock.
- A solved ticket past the window closes once across two overlapping worker runs; a reply one minute before the deadline
  reopens it and the job leaves it alone.
- A reply on a closed ticket creates a linked follow-up and not a message on the closed one.
- A template with an unknown variable is refused when saved.

## Risks

- Merge has no undo (decided): the response states the moved counts and the event keeps the original ids, so a Tenant can
  reconstruct the history from the audit trail.
