---
phase: 16
title: "Threading, loop guard and HTML safety"
status: pending
priority: P1
effort: "L"
dependencies: [15]
---

# Phase 16: Threading, Loop Guard and HTML Safety

## Goal

An inbound reply lands in the ticket it belongs to, automatic mail and mail loops create nothing, and the HTML an agent sees is
safe and free of quoted history.

## Context

Phase 15 creates a ticket for every accepted message. This phase puts the three decisions in front of that step, using the
thread token minted in phase 12 and the message-id table. Mailer never sends `In-Reply-To` for us (the command contract
forbids it), so the **token in the reply address is the primary signal** and message ids are a secondary one.

## Files to create

- `helpdesk/contexts/channel/.../email/**`: `ThreadResolver`, `LoopGuard`, `HtmlSanitizer`, `QuotedHistoryStripper`,
  `SenderRateCap`, `UnthreadedMarker`
- Migration `V12__inbound_guards.sql`: `sender_rate` counters, `tickets.unthreaded`, `messages.sender_mismatch`,
  `inbound_mail.guard_outcome`, jOOQ regenerated
- Tests: `ThreadResolverTest` (every order of signals), `LoopGuardTest` (one case per rule), `HtmlSanitizerTest` (OWASP XSS
  samples, `javascript:` links, CSS expressions, `<img onerror>`, SVG, tracking pixels), `QuotedHistoryStripperTest` against
  vectors in several clients' quoting styles, `InboundReplyIntegrationTest`, `SenderRateCapTest`

## Tasks

1. **Thread resolution order.** (a) a valid token in `To`, `Cc` or `Delivered-To` of the tenant's domain, mac verified with any
   key id; (b) `In-Reply-To` or any `References` entry found in `message_ids` for this Tenant; (c) a `[#<no>]` subject token
   **only** when the sender equals the ticket's requester or a participant. The first hit wins; a token whose ticket is in another
   Tenant is ignored and never confirms existence. No hit means a new ticket with `unthreaded` set when the subject starts with
   a reply prefix or `In-Reply-To` was present, so an agent can merge it.
2. **Apply the intake policy.** A threaded message goes through the shared reply intake policy from phase 8: reopen a solved
   or pending ticket, add to an open one, create a linked follow-up for a closed one. **Only when the sender is the requester or
   a participant.** The token is a bearer capability that travels with a forwarded mail, so a token hit from any other sender
   stores the message `INBOUND` with `sender_mismatch = true`, shows it to agents as "from an unknown sender", and does **not**
   reopen the ticket, resume an SLA timer or send a webhook of type `ticket.replied` with `author.kind = REQUESTER`; an agent
   can accept it (adds the sender as a participant) with one call.
3. **Loop guard, ordered.** Drop (record outcome `AUTOMATIC`, create nothing, send nothing) when: `Auto-Submitted` is not `no`;
   `Precedence` is `bulk`, `list` or `junk`; `X-Auto-Response-Suppress` is present; `List-Id` or `List-Unsubscribe` is present;
   the return path is null (`<>`) or the sender's local part is `mailer-daemon`, `postmaster`, `noreply` or `no-reply`;
   the sender is one of the Tenant's own support addresses (self-mail); or the sender has more than the cap of messages
   (default 20 per hour) into one ticket or 60 per hour to one Tenant. A dropped message is still stored in `inbound_mail`
   with its outcome so nothing disappears silently.
4. **No outgoing reply from intake.** Intake never sends mail; the only automatic mail in helpdesk is the form confirmation, so a
   loop needs an agent to start it.
5. **Sanitise.** Sanitise HTML with an allowlist policy (text formatting, lists, tables, links with `rel=noopener nofollow`
   restricted to `http`, `https`, `mailto`, inline images only as `cid:` mapped to attachments, no remote images by default); store
   the sanitised HTML and the text; the text part is what the API returns by default.
6. **Strip quoted history.** Remove the quoted reply and the signature separator patterns of common clients; keep the full
   original in `inbound_mail` retention (30 days, a setting) so nothing is lost; a failed strip keeps the whole body.
7. **Message ids.** Store every inbound `Message-ID` and the outbound `providerMessageId` in `message_ids`, tenant-scoped.

## Verification

- A message carrying a valid token from an address that is neither requester nor participant is stored flagged
  `sender_mismatch`, changes no status, resumes no timer and sends no requester-authored webhook; accepting it works once.
- A reply to a helpdesk mail resolves by token with `In-Reply-To` stripped, by `In-Reply-To` with the token stripped, and by the
  subject token only from the requester; a forwarded mail from another sender with the subject token creates a new ticket.
- An out-of-office reply, a bounce, a list message, a no-sender message and a self-mail each create no ticket and no
  outbound command; the dropped message is recorded with its reason.
- Two mailboxes set up to auto-reply to each other produce no more than the first inbound message per rule and then silence.
- The XSS corpus is neutralised; `git grep` shows the sanitiser is the only writer of stored HTML.
- Quoted-history stripping keeps the new text for the sampled clients and keeps everything when unsure.

## Risks

- Over-eager loop rules can drop a real customer who happens to send from a `noreply`-style address: dropped mail is recorded
  and readable through `GET /inbound-mail?outcome=AUTOMATIC` (read by `helpdesk:ticket:read`) so an agent can recover it.
