---
title: "Helpdesk app — multi-channel tickets for a Tenant's customers (email on the Tenant's own domain, public form, phone, generic channel API)"
description: "An API-only helpdesk/ deployable with a channel-neutral ticket and message model, queues, assignment, SLA with business hours, attachments, merge, auto-close, canned replies and eight signed webhooks; email in and out runs on the Tenant's own domain through a thin inbound adapter and mailer (extended only to carry outbound attachments), built last."
status: pending
priority: P1
effort: "19 phases, 9 × M, 10 × L"
branch: dev
tags: [feature, backend, api, database, infra, security]
blockedBy: []
blocks: []
created: 2026-11-17
---

# Helpdesk App

## Overview

A Tenant's customers have nowhere to ask for help: the suite can sell to them (billing), reach them (mailroom), survey them,
book them and reward them, but a question or complaint has no home. This plan adds `helpdesk/`, an API-only app that turns
incoming contact into tickets that agents (Platform users of the Tenant) work to a close, with deadlines.

The suite rule "apps never link; the Tenant System is the only integrator" is **kept**. A ticket knows its requester by an
email, a phone or a channel reference and optionally a Tenant-set `externalRef`; helpdesk never reads customer, loyalty,
survey, booking or billing data, and the Tenant System attaches whatever context it wants when a webhook arrives.

This **reverses** one earlier decision: `docs/customer.md` lists "case, complaint and ticket handling" as a non-goal
(2026-11-16). The user confirmed the reversal in the `/ak-advise` interview of 2026-11-17 (memory:
`helpdesk-app-decisions`); phase 1 edits that document.

The user chose the full scope on purpose (all four channel kinds, SLA, queues, attachments, merge, auto-close, canned replies,
the Tenant's own domain for email). The recommended ordering, not a cut, keeps the risk in check: the channel-neutral core and
the three non-email channels are proven first; email, the most expensive part, is built last behind the same model.

## Outcome contract

**Goals**

1. A customer's contact on any of four channels becomes a ticket in the right Tenant, queue and thread.
2. An agent works the ticket through the API: read, reply to the customer, add an internal note, assign, change status,
   attach files, merge duplicates, use a canned reply.
3. A reply to an email, form or tracking-link ticket reaches the customer by email and the customer's answer lands in the
   same ticket; a reply on a generic channel reaches the Tenant System as a webhook to deliver on its own channel.
4. Deadlines are visible and enforced: first-response and resolution timers run in business hours, pause while waiting for the
   customer, and fire `sla.at-risk` and `sla.breached` once each.
5. The Tenant's own system learns every actionable fact through eight signed webhooks (plus `requester.erased`) and links tickets to its customers.
6. Hostile input is contained: forged senders, mail loops, malicious HTML and files, bot floods and cross-Tenant reads.

**Done means**

- `helpdesk/` builds, migrates and tests green; ArchUnit, Modulith and the isolation gate pass; api, worker and migrate
  runtimes start on the compose stack.
- `ops/smoke/helpdesk.sh`, playing the customer and the Tenant System, passes on all four channels: a raw MIME message
  injected at the inbound endpoint becomes a ticket; an agent reply leaves through mailer with a reply address that carries the
  thread token; the customer's answer, injected back, lands in the same ticket and creates no new one; a form submission
  creates a ticket and a confirmation mail whose tracking link works; a phone ticket is created by an agent; a generic-channel
  message is pushed in and the agent's reply arrives as a `ticket.replied` webhook.
- An auto-reply, a bounce, a list message and a mail with no sender create no ticket and trigger no outgoing mail.
- `sla.at-risk` and `sla.breached` fire exactly once per ticket and timer, also across a worker restart, and a timer pauses
  while the ticket waits for the customer and respects business hours, time zone and holidays.
- Merge moves messages and attachments into the target and closes the source; auto-close closes a solved ticket after the
  Tenant's N days; a customer reply reopens a ticket within the window and starts a linked follow-up after it.
- Tenant A cannot read or change tenant B's tickets, messages, attachments, forms, domains or webhooks on any table or
  endpoint (row-level security test per table plus an API probe); inbound mail for tenant A's domain never reaches tenant B.
- An attachment is checked for size, declared type and content type, stored under a key that never uses the file name, and is
  only downloadable through a presigned URL with `Content-Disposition: attachment`; inbound HTML is sanitised before storage.
- `contracts/openapi/helpdesk.yaml`, `contracts/permissions/helpdesk.yaml`, `contracts/events/helpdesk/*.schema.json` exist
  and contract tests prove the running app matches them.
- `docs/helpdesk.md` exists; `docs/customer.md` no longer lists ticket handling as a non-goal and names helpdesk as the home
  for it; `docs/README.md`, `docs/overview-and-scope.md` and `contracts/messaging/README.md` are updated.
- An agent's reply with files reaches an email or form customer with the files attached to the mail itself (mailer gains
  an optional, bounded `attachments` field; the existing command without it behaves exactly as before, proved by mailer's
  existing tests passing unchanged).
- `DELETE /requesters/{id}` removes that person's tickets, messages, attachments and their objects, raw inbound mail, timers,
  conversations and mail ledger rows, leaves a tombstone with no personal data, and sends `requester.erased`.
- No change under `survey/`, `customer/`, `loyalty/`, `mailroom/`, `attribution/`, `booking/`, `workflow/` or `tenant/` Java.
  `mailer/` changes only for outbound attachments (phase 11) and nothing else (no inbound, no new headers). `git diff --stat`
  for every phase shows only the files that phase lists.

**Non-goals**

A Console or any UI; telephony (call routing, recording, IVR, click-to-call) and any direct social-network connector (the
generic channel API is how a Tenant brings chat, Zalo, Facebook or an app in); changing mailer to receive mail or to carry
new headers (its one allowed change is outbound attachments, a user decision of 2026-11-17); an automatic acknowledgement
mail for an email-created ticket (decided: none, to avoid backscatter and loops); any link to another suite app; built-in CSAT or surveys (the Tenant wires `ticket.closed` to survey itself);
reports and analytics; knowledge base or customer self-service portal beyond the tracking link; AI triage or auto-answers;
multi-brand mailboxes inside one Tenant; billing a Tenant for helpdesk use; malware scanning of attachments (see risks).

**Constraints**

Tenant from the host and the token for every authenticated and public route, and from the verified domain row for inbound mail
only; API only; the Tenant System is the only integrator (`externalRef` is set by the Tenant, never looked up); agents are
Platform users and their rights come from the replicated `user_grants`; replies leave through mailer under the Tenant's own
sender identity and mailer changes only to carry outbound attachments; the Tenant's own domain is required for inbound email (no suite-hosted address);
inbound mail is never trusted for identity (sender verification result is recorded, never assumed); the existing suite
patterns (jOOQ with committed generated schema, RLS, suite tokens, ETag and `If-Match`, `core/` modules, outbox and inbox,
`Idempotency-Key`, the shared list query contract, api/worker/migrate runtimes); conventional commits without AI references; no
plan or phase number in code, migration or test names.

## Evidence from the scout (verified in source on 2026-11-17)

| Finding | Source | Consequence |
|---|---|---|
| `docs/customer.md` lists "Case, complaint and ticket handling" as a non-goal | `docs/customer.md:19`, `:232` | Phase 1 edits both lines; helpdesk is the named home |
| Mailer is delivery-only: "a mailbox" is a non-goal and SES asynchronous events are not consumed | `docs/mailer.md:13` | Inbound lives in helpdesk behind an adapter; mailer gets no inbound role |
| `email.send` allows only two extra headers (`List-Unsubscribe`, `List-Unsubscribe-Post`, `maxProperties: 2`); the transports can carry any header (`SesV2EmailSender` maps a header map to SES `MessageHeader`, `SmtpEmailSender` calls `setHeader`) but the contract refuses `In-Reply-To` and `References` | `contracts/commands/email.send.schema.json`, `mailer/contexts/email/src/adapter/java/dev/ngb/mailer/email/adapter/delivery/{ses/SesV2EmailSender,smtp/SmtpEmailSender}.java` | Threading cannot rely on reply headers we send. It uses a token in the reply address (next row), so threading needs no mailer change |
| `replyTo` is checked only by **domain** against the resolved sender's allowlist (empty allowlist means the sender's own domain), so `support+<token>@tenant-domain` is accepted | `mailer/.../credential/aws/ConfiguredSenderResolver.java` (`allowedReplyTo`) | The thread token travels in `replyTo`; replies come back to the Tenant's own domain with the token in the `To` header |
| `email-delivery.changed` carries `requestId`, `recipient`, `status`, `providerMessageId` (diagnostic, "a consumer must not parse it") | `contracts/events/mailer/email-delivery.changed.schema.json` | Delivery state is matched by `requestId`; `providerMessageId` is stored only as an opaque secondary lookup for `In-Reply-To` |
| Mailer registers a tenant when it accepts a command, and a sender is a configured `fromKey` whose address belongs to mailer's credential row | `docs/mailer.md`, `email.send.schema.json` | Each Tenant needs a mailer credential whose from address is its support address (existing onboarding, `docs/mailer-tenant-onboarding.md`); helpdesk stores only the `fromKey` |
| mailroom's broker user may write `mailer.commands` and read `mailer.events` on its own queues; an app never binds to another app's business exchange, and mailer's two exchanges are infrastructure | `ops/rabbitmq/declare-users.sh:99-103`, `contracts/messaging/README.md:99` | Helpdesk's user gets the same grants as mailroom's, no more |
| `core-webhook` provides signer, sender, retry, destination policy; header sets are owned by each app (`BookingWebhookHeaders`, `CustomerWebhookHeaders`) | `core/core-webhook/...`, `booking/contexts/webhook/.../BookingWebhookHeaders.java` | `HelpdeskWebhookHeaders` lives in helpdesk; no core change |
| The replica already holds effective permissions per user in `user_grants` | `survey/apps/app-boot/src/main/resources/db/migration/V1__baseline.sql:82` | Agent eligibility and assignment check `user_grants`; helpdesk owns agent groups, not Platform roles |
| Survey has presigned PUT and GET uploads with a server-side content check and a purge job; there is no shared storage module in `core/` | `survey/contexts/collection/.../upload/**`, `docs/survey.md:135-201` | Copy the pattern into helpdesk; never import Survey |
| Booking's public gate: HMAC-signed rotating opaque tokens, a guard filter before authentication, per-instance token bucket keyed by an HMAC of the client IP, honeypot, permit-all `/p/**` | `plans/261116-2239-booking-app/phase-13-public-gate.md` | The form and tracking-link surface copies that shape |
| `web/` holds only a README; there is no Console | `ls web` | API only, as confirmed |
| Platform's catalog is packaged at build time, so the new app's permission file needs a Platform rebuild before a Tenant can enable `helpdesk` | `plans/261116-2239-booking-app/phase-02-identity-and-ops-seams.md` | Noted in `docs/helpdesk.md` and phase 3 |
| `email.send` has `additionalProperties: false` and no attachment field; bodies are capped at 262144 characters | `contracts/commands/email.send.schema.json` | Phase 11 adds an optional bounded `attachments` field (user decision 2026-11-17); how mailer persists a command decides inline versus reference and is read first |
| Inbound mail has no existing infrastructure: no MX, no SES receiving rule, no SMTP listener | `docs/mailer.md`, `ops/terraform/*`, `docker-compose.yml` | Phase 2 is a decision spike; phase 17 builds the adapters |

## Key decisions (settled in the advise interview unless marked)

- **Name and wiring.** `helpdesk`, entitlement and audience `helpdesk`, package `dev.ngb.helpdesk`, prefix
  `/helpdesk/api/v1`, public prefix `/helpdesk/p/`, inbound prefix `/helpdesk/inbound/`, queue `helpdesk.platform-events`.
  *(planner default)*
- **Channel-neutral core.** Every ticket has a `channel` and every message a `source`, a `direction` (INBOUND, OUTBOUND) and a
  `visibility` (PUBLIC, INTERNAL). A channel declares how a reply is delivered: `MAIL` (email, form, tracking link), `WEBHOOK`
  (generic channel) or `NONE` (phone). No channel pretends to support another's primitives.
- **Threading without a mailer change.** The reply address is `<local>+t<ticketNo>.<hmac>@<tenant-domain>`; an inbound mail
  resolves its ticket by (1) a valid token in `To`/`Delivered-To`, (2) `In-Reply-To`/`References` against stored message ids,
  (3) the subject token `[#<ticketNo>]` only when the sender is the ticket's requester. A reply that cannot be placed
  becomes a new ticket, flagged `unthreaded` so an agent can merge it. *(planner default)*
- **Inbound tenant.** The inbound endpoint is not on a Tenant host. The Tenant comes from the recipient domain matched to a
  verified `helpdesk_domains` row (DNS TXT ownership proof); an unverified or unknown domain is refused. The caller is the
  adapter, authenticated by an HMAC-signed request with a key id and a replay window, never by a user token.
- **Requesters.** One row per `(tenant, normalised email)` for email and form, or `(tenant, channel, contactRef)` for phone
  and generic channel; no automatic merge across kinds; the Tenant sets `externalRef` by `PATCH`.
- **Agents.** Platform users holding a helpdesk permission in `user_grants`. Helpdesk owns agent groups (users added
  explicitly, permission `helpdesk:group:manage`); a Platform role is never a group. Round-robin picks the next group member
  who holds `helpdesk:ticket:reply` at assignment time.
- **Status machine.** `NEW`, `OPEN`, `PENDING` (waiting for the customer), `SOLVED`, `CLOSED`. A customer reply moves `PENDING`
  and `SOLVED` to `OPEN`; `CLOSED` is terminal and a later reply creates a follow-up ticket linked by `followUpOf`.
- **SLA.** Per-queue policy by priority (first response, resolution), business hours per Tenant (weekly windows, IANA zone,
  holidays), the timer pauses in `PENDING`, `at-risk` fires at a configurable fraction (default 80 %), each of `at-risk` and
  `breached` fires once per ticket and timer. Timers are rows swept with `FOR UPDATE SKIP LOCKED`; helpdesk never calls
  workflow or any app.
- **Auto-close and merge.** `SOLVED` closes after `autoCloseDays` (default 7, Tenant setting); merge is one-way, moves
  messages and attachments, closes the source with `mergedInto` and has no undo. *(planner default)*
- **Webhooks.** `ticket.created`, `ticket.replied` (both directions, `direction` and `channel` in the payload so a Tenant can
  deliver a generic-channel reply), `ticket.assigned`, `ticket.status-changed`, `sla.at-risk`, `sla.breached`, `ticket.merged`,
  `ticket.closed`, plus `requester.erased` from the erasure endpoint; signed with `Helpdesk-*` headers, the Survey and Booking copy pattern.
- **Form and tracking link.** A form is a Tenant-defined set of typed fields plus a mandatory email field; a submission
  creates a ticket, sends a confirmation mail, and the mail carries a tracking link (HMAC token) where the customer reads the
  public messages and adds a reply. The public surface is guarded as booking's gate is.
- **Attachments.** 10 MB each, 25 MB per message, extension and content-type allowlist with a content check, executable types
  refused, key `tenant/{tenantId}/helpdesk/{ticketId}/{attachmentId}`, retention follows the ticket, purge job for orphans.
  *(planner default)*
- **Sanitising and loops.** Inbound HTML is sanitised server-side and the quoted history is stripped; `Auto-Submitted`,
  `Precedence: bulk|list|junk`, a null return path, a mail from `noreply`/`mailer-daemon`, and a per-sender rate cap create no
  ticket and send nothing back; the helpdesk never mails the sender of an inbound message on its own (no auto-acknowledge by
  email, which avoids backscatter), only the form sends a confirmation.

## Phases

| # | Phase | Effort | Depends on | Status |
|---|---|---|---|---|
| 1 | [Scope, contracts and docs](phase-01-scope-contracts-and-docs.md) | M | none | pending |
| 2 | [Inbound transport spike and decision](phase-02-inbound-transport-spike-and-decision.md) | M | 1 | pending |
| 3 | [Identity and ops seams](phase-03-identity-and-ops-seams.md) | M | 1 | pending |
| 4 | [Build skeleton, replica and gates](phase-04-build-skeleton-replica-and-gates.md) | L | 1, 3 | pending |
| 5 | [Ticket core: requesters, queues, tickets, messages](phase-05-ticket-core.md) | L | 4 | pending |
| 6 | [Agents, groups and assignment](phase-06-agents-groups-and-assignment.md) | M | 5 | pending |
| 7 | [Attachments](phase-07-attachments.md) | L | 5 | pending |
| 8 | [Canned replies, merge, auto-close and reopen](phase-08-canned-replies-merge-auto-close.md) | L | 6, 7 | pending |
| 9 | [Signed webhooks](phase-09-signed-webhooks.md) | M | 5 | pending |
| 10 | [Channel registry, phone and generic channel API](phase-10-channels-phone-and-generic-api.md) | M | 5, 9 | pending |
| 11 | [Mailer: outbound attachments](phase-11-mailer-outbound-attachments.md) | M | 1 | pending |
| 12 | [Outbound mail through mailer and delivery results](phase-12-outbound-mail-and-delivery-results.md) | L | 3, 5, 7, 11 | pending |
| 13 | [Public form and tracking link](phase-13-public-form-and-tracking-link.md) | L | 9, 12 | pending |
| 14 | [SLA and business hours](phase-14-sla-and-business-hours.md) | L | 5, 9 | pending |
| 15 | [Email intake: domains, signed endpoint, parse and route](phase-15-email-intake.md) | L | 2, 5, 7, 12 | pending |
| 16 | [Threading, loop guard and HTML safety](phase-16-threading-loop-guard-and-html-safety.md) | L | 15 | pending |
| 17 | [Inbound transport adapters and the local injector](phase-17-inbound-adapters-and-injector.md) | M | 2, 15 | pending |
| 18 | [Requester erasure](phase-18-requester-erasure.md) | M | 7, 12, 16 | pending |
| 19 | [End-to-end smoke, isolation, ops and docs](phase-19-smoke-isolation-ops-and-docs.md) | L | 8, 10, 13, 14, 17, 18 | pending |

Phases 5 to 14 contain no email-in work; they are provable on a stack with no inbound mail, which is the point of the order.
Phase 11 is the only phase that edits another app; it is a user decision of 2026-11-17 and backward compatible.

## Risk register

| Risk | Likelihood | Impact | Mitigation | Owner phase |
|---|---|---|---|---|
| SES receiving needs a verified domain in the receiving AWS account, which conflicts with the Tenant sending from its own AWS identity | Medium | High | Spike decides before any inbound code; fallback is the adapter running an SMTP listener behind the same endpoint, so only phase 17 changes | 2, 17 |
| Plus-addressing is stripped or rewritten by a customer's or a forwarder's mail system, losing the token | Medium | Medium | Three-step resolution (token, message ids, subject token plus requester); an `unthreaded` ticket is mergeable by an agent | 16 |
| The thread token is a bearer capability and travels with a forwarded mail | Medium | Medium | A token hit from a sender who is not the requester or a participant is stored flagged `sender_mismatch`, changes no status and resumes no timer until an agent accepts it | 16 |
| Reply loops with out-of-office or other helpdesks | High | High | Loop guard (headers, null return path, no-reply senders, rate cap per sender) and no automatic mail back on inbound | 16 |
| Malicious HTML or file reaches an agent's client | Medium | High | Server-side sanitising, content check and allowlist, download only by presigned `attachment`; no scanning in v1, documented as a residual risk | 7, 16 |
| Forged sender passes as a real customer | High | Medium | Record SPF/DKIM/DMARC results from the adapter as `senderVerification`, never merge or trust identity by address, expose the flag to agents | 15 |
| The form becomes a spam or mail-bombing relay (confirmation mail to an arbitrary address) | Medium | High | Rate limit by client fingerprint and by target address, honeypot, per-Tenant daily cap, no confirmation to an address already confirmed in the last hour | 13 |
| Business-hours arithmetic bugs (DST, holidays, windows crossing midnight) | Medium | Medium | A pure calendar module with property tests against a reference implementation in the test code | 14 |
| An SLA timer fires twice after a worker crash | Medium | Medium | Fire is a compare-and-set on the timer row inside the same transaction as the outbox event | 14 |
| Tenant domain verification is bypassed to hijack another Tenant's inbound mail | Low | High | TXT proof bound to the Tenant, one verified owner per domain, re-check job, cross-tenant test | 15 |
| Mailer's outbound attachments change breaks existing producers or bloats its delivery ledger | Medium | High | Optional field only; the existing mailer tests must pass unchanged; a hard size cap; the phase starts by reading how mailer persists a command and stops to ask if attachments cannot be held reasonably | 11 |
| Erasure leaves a copy behind (object, raw inbound mail, outbox row, webhook delivery body) | Medium | High | An inventory test lists every table and bucket prefix that can hold a requester's data and fails when a new one is not covered | 18 |
| The scope (four channels, SLA, queues, attachments, merge, erasure, own domain, one mailer change) takes far longer than the other apps | High | Medium | Order isolates email last; every phase has a self-contained acceptance check so the plan can stop after phase 14 with a working non-email helpdesk | all |
| Mailer cannot send from a Tenant's support address because the Tenant has no credential row | Medium | Medium | Settings validation fails with a clear error until the Tenant has a mailer credential; documented in the onboarding doc | 12 |

## Red Team Review (2026-11-17, planner self-review, four personas)

| Persona | Finding | Resolution |
|---|---|---|
| Assumptions | The advise brief said "stop and ask if mailer cannot carry thread headers". Source shows the command contract refuses `In-Reply-To`/`References`, but `replyTo` is checked by domain only | Threading uses a token in the reply address; no header change. Recorded in the evidence table and phase 12, 16 |
| Failure | A customer's forwarded mail carries the thread token, so a third party could inject messages, reopen the ticket and resume SLA timers | Phase 16 task 2: a token hit from a non-requester/non-participant is stored flagged `sender_mismatch`, changes nothing until an agent accepts it |
| Failure | Compose's MinIO bootstrap creates only survey's bucket and uses `SURVEY_S3_*` credentials | Phase 3 task 3 appends a helpdesk bucket and separate `HELPDESK_S3_*` keys |
| Scope | 19 phases is the largest plan so far | Kept (user chose the full scope); the order allows stopping after phase 14 with a working non-email helpdesk |
| Security | The inbound endpoint is unauthenticated at the proxy and tenant-from-domain breaks "tenant from the host only" | Signed requests with replay window, verified-domain tenancy with a cross-tenant test (phase 15), network restriction (phase 17), documented as the single exception |
| Security | The form can mail an arbitrary address | Per-address and per-fingerprint limits, daily cap, one-hour dedupe (phase 13) |

## Verification Results (2026-11-17)

- Claims checked: 40 (paths, symbols, contract fields, existing scripts and tables) | Verified: 40 | Failed: 0 | Unverified: 4
- Tier: Full (19 phases). Method: `ls`/`grep` against the repository for every cited path and symbol; contract fields read from
  `contracts/commands/email.send.schema.json`, `contracts/events/mailer/email-delivery.changed.schema.json`,
  `ConfiguredSenderResolver.java`, `SesV2EmailSender.java`, `SmtpEmailSender.java`, `docker-compose.yml`, `ops/rabbitmq/declare-users.sh`.
- Unverified (named, with the phase that resolves them): how mailer persists a command for retry (phase 11 design gate);
  whether SES receiving can serve a Tenant-owned domain (phase 2); whether the Mailpit-to-SES behaviour of a plus-addressed
  `replyTo` matches (phase 2 question 6); the exact survey MinIO bootstrap commands to append to (phase 3, re-read first).

## Validation Log

### Session 1 (2026-11-17)

| # | Question | Answer | Propagated to |
|---|---|---|---|
| 1 | Files in agent replies to email/form customers: signed links, none, or extend mailer? | **Extend mailer to send real attachments** (user chose against the recommended signed links) | New phase 11 (mailer outbound attachments), phase 12, plan constraints, non-goals, Done means, risk register |
| 2 | Acknowledge email-created tickets automatically? | **No automatic acknowledgement** (recommended) | Plan non-goals, key decisions, phase 12 task 6, phase 16 task 4 |
| 3 | Requester erasure in v1? | **Yes, add to v1** | New phase 18, `requester.erased` (ninth event), permission `helpdesk:requester:erase`, phases 1, 7, 9, 19 |

Earlier interview decisions (advise, 2026-11-17) stand: API only; four channels; full scope; the Tenant's own domain for email.

### Whole-Plan Consistency Sweep

- Renumbered phases 11 to 17 to 12 to 19 after inserting phases 11 and 18; every `phase N` reference, frontmatter `phase`,
  `dependencies`, heading and table row was rechecked (script plus eyeball): headings, front matter and file names agree for all
  19 phases; `ak plan validate` passes.
- Searched for stale terms: "mailer is not modified", "mailer untouched", "links only", "eight signed webhooks" now state the
  phase 11 exception, the signed-attachment decision and the ninth event; no contradiction remains.

## Unresolved questions

1. **Inbound transport** (SES receiving or an SMTP listener): decided in phase 2 with evidence; the plan stops to ask if neither
   fits the deployment.
2. **Mailer attachments, inline or by reference**: decided in phase 11's design gate; default inline with a 10 MB cap.
3. **Threading headers**: phase 11 already edits mailer, so adding `In-Reply-To`/`References` to the command's header allowlist
   would be cheap, but nothing requires it (the token carries threading) and it was not requested, so it is **not** planned.
   Say so if you want it.
4. **Defaults the planner chose, reversible**: phone ticket first message is `INTERNAL`; raw inbound mail retained 30 days;
   attachments kept with the ticket; at-risk at 80 %; auto-close 7 days.
