---
phase: 10
title: "Channel registry, phone and generic channel API"
status: pending
priority: P1
effort: "M"
dependencies: [5, 9]
---

# Phase 10: Channel Registry, Phone and Generic Channel API

## Goal

The two non-mail channels work end to end: an agent keys in a phone call, and a Tenant System pushes messages from any channel it
runs (chat, Zalo, Facebook, an app) and receives the agent's replies as webhooks.

## Context

A channel is a Tenant-configured row with a name and a **delivery capability**: `MAIL`, `WEBHOOK` or `NONE`. The capability, not
the channel name, decides what a reply does, so no channel pretends to support another's primitives. Built-in channels are
`email`, `form` (capability `MAIL`) and `phone` (`NONE`); a Tenant adds generic channels with capability `WEBHOOK`.

## Files to create

- Migration `V7__channels.sql`: `channels` (tenant, `name` unique per tenant, `kind` BUILTIN|GENERIC, `delivery`,
  `default_queue_id`, `enabled`), `conversations` (tenant, channel, `conversation_ref`, `ticket_id`, `requester_id`, unique
  `(tenant, channel, conversation_ref)`), RLS, jOOQ regenerated
- `helpdesk/contexts/channel/.../**`: `Channel`, `ChannelRegistry`, `PushChannelMessageUseCase`, `RecordPhoneTicketUseCase`,
  `ChannelController` (`helpdesk:channel:manage`), `ChannelPushController` (`helpdesk:channel:push`)
- Tests: `ChannelCapabilityTest`, `PhoneTicketIntegrationTest`, `ChannelPushIdempotencyTest`, `ConversationRoutingTest`,
  `ChannelRowLevelSecurityTest`

## Tasks

1. **Registry.** `GET|POST|PATCH /channels`; a generic channel name matches `^[a-z][a-z0-9-]{1,31}$` and cannot reuse a built-in
   name; capability `MAIL` is reserved for built-ins.
2. **Phone.** `POST /tickets` with `channel=phone` accepts `callerName`, `callerPhone`, `callDirection` (INBOUND, OUTBOUND),
   `durationSeconds`, `summary` and records the summary as the first `INTERNAL`-or-`PUBLIC` message chosen by `visibility`
   (default `INTERNAL`: a call note is not a customer-facing reply); a reply on a phone ticket is refused with
   `409 channel_cannot_deliver`, a note is allowed.
3. **Generic push.** `POST /channels/{channel}/messages` with `conversationRef`, requester (`contactRef`, `name`, optional
   `email`, `externalRef`), `text`, optional attachment ids and an `Idempotency-Key`: it finds the open ticket of
   `(channel, conversationRef)` or creates one per the shared reply intake policy (phase 8), adds the message as `INBOUND`.
4. **Generic reply.** An agent's public reply on a `WEBHOOK` ticket is stored `OUTBOUND` and emitted as `ticket.replied`
   carrying `conversationRef`; there is no delivery receipt in v1, so the message's `delivery` is `HANDED_TO_WEBHOOK`.
5. **Disabled channel.** A push to a disabled or unknown channel is `404`; a channel may be disabled with open tickets and
   those stay workable.

## Verification

- A phone ticket is created by an agent, a note is added, a reply is refused, a webhook `ticket.created` is delivered.
- A push with the same `Idempotency-Key` twice creates one message; a push to a closed ticket's conversation creates a linked
  follow-up; two conversations in one channel never mix.
- A reply on a `WEBHOOK` ticket produces `ticket.replied` with the `conversationRef`, and none for an internal note.
- A reply on a `MAIL` ticket reaches phase 12's outbound path (asserted there); the capability table has one test per capability.

## Risks

- A Tenant System that never answers webhooks looks like a delivered reply: the `delivery` value says only "handed to
  webhook", and `docs/helpdesk.md` states that confirming delivery on the Tenant's channel is the Tenant's job.
