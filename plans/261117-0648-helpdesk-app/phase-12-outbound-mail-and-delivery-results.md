---
phase: 12
title: "Outbound mail through mailer and delivery results"
status: pending
priority: P1
effort: "L"
dependencies: [3, 5, 7, 11]
---

# Phase 12: Outbound Mail Through Mailer and Delivery Results

## Goal

An agent's public reply on a mail-capable ticket leaves as an `email.send` command under the Tenant's own sender, with a reply
address that carries the thread token, and the delivery outcome is recorded on the message.

## Context

Mailer stays delivery-only; phase 11 adds only an optional `attachments` field to the command. The command schema (`contracts/commands/email.send.schema.json`) takes already
rendered `subject`, `html`, `text`, `to`, `cc`, `fromKey`, `fromName`, `replyTo` and a `requestId` idempotency key; the
`replyTo` is checked by **domain** against the sender's allowlist, so `support+t<no>.<mac>@<tenant-domain>` is accepted when its
domain is the sender's. Mailroom is the precedent: it produces `email.send` and consumes `email-delivery.changed` on its own
queue, and its broker user has exactly the grants phase 3 gave helpdesk. Helpdesk renders nothing beyond escaping the agent's
text into a plain wrapper.

## Files to create

- Migration `V8__mail.sql`: `mail_settings` (tenant, `from_key`, `from_name`, `support_address`, `thread_key_id`),
  `outbound_mail` (tenant, ticket, message, `request_id`, `status` PENDING|SENT|DEAD, `provider_message_id`, attempts),
  `message_ids` (tenant, `message_id`, `ticket_id`, `source` INBOUND|OUTBOUND_PROVIDER), RLS, jOOQ regenerated
- `helpdesk/contexts/mail/.../**`: `ThreadToken` (mint and verify), `ReplyMailComposer`, `SendReplyUseCase` (writes message and
  the outbox row for `email.send` in one transaction), `MailerEventsListener` for `email-delivery.changed`, `MailSettingsController`
  (`helpdesk:settings:manage`)
- Tests: `ThreadTokenTest` (round trip, tamper, key rotation), `ReplyMailComposerTest` (subject token, header-injection
  refusal, quoted history), `SendReplyIntegrationTest` (against mailer and Mailpit on the compose stack),
  `DeliveryResultTest` (out-of-order and replayed events), `MailRowLevelSecurityTest`

## Tasks

1. **Settings.** `PUT /settings/mail` sets `fromKey`, display name and the support address; saving checks the address domain
   against a verified `helpdesk_domains` row once phase 15 exists, and before that only checks syntax; if the Tenant has no mailer
   credential the first send fails with a clear `mail_sender_unavailable` that is surfaced on the message.
2. **Thread token.** Local part `<local>+t<ticketNo>.<mac>`, mac from a keyed HMAC with a key id in `mail_settings`; keys are
   rotated by adding a key and keeping old ones for verification only. The subject carries `[#<ticketNo>]`.
3. **Compose.** Subject is `Re: <ticket subject> [#<no>]`; the body is the agent's text escaped into HTML and a plain-text part;
   recipients are the requester plus participants (cap 20); the message's attachments go into the command's `attachments`
   field within phase 11's caps (a reply over the cap is refused with `422 attachments_too_large` before anything is sent, and
   the agent is told the limit); the tracking link is **not** added to agent replies.
4. **Send.** The `requestId` is `helpdesk:<tenant>:<messageId>`; a repeated command never sends twice (mailer's ledger) and a
   retried reply reuses the same id; `fromKey` is the tenant's configured key.
5. **Results.** `MailerEventsListener` matches `(tenant, requestId)`, sets `outbound_mail.status`, stores `providerMessageId`
   as an opaque lookup in `message_ids`, ignores unknown status values, keeps the newest `completedAt`, and writes a delivery
   fact onto the message that an agent reads; a `DEAD` result leaves the ticket in place and adds an internal event.
6. **No acknowledgement mail.** A new ticket from email sends nothing back (backscatter avoidance); only the form (phase 13)
   sends a confirmation.

## Verification

- A reply on an email ticket produces one `email.send` on `mailer.commands`; mailer delivers it to Mailpit with `Reply-To`
  carrying the token and the subject carrying `[#no]`.
- A redelivered `email-delivery.changed` and an out-of-order older one change nothing.
- A subject or display name with CR/LF is refused before any command is written.
- A reply with two attachments arrives in Mailpit with both parts; a reply over the cap is `422` and writes no command.
- A Tenant without a mailer credential gets `mail_sender_unavailable` on the message and no stuck state.
- `git diff --stat` for this phase shows no change under `mailer/` (phase 11 owns that change).

## Risks

- Mailer's `replyTo` allowlist can be stricter than the empty default for a Tenant that configured one: the setting check
  reports the exact reason, and onboarding documentation says the allowlist must include the support domain.
- Bytes of attachments are read from the object store at send time; a missing object fails the reply with a clear error and
  leaves the message unsent, never half-sent.
