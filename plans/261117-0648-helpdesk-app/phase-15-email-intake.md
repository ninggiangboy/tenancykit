---
phase: 15
title: "Email intake: domains, signed endpoint, parse and route"
status: pending
priority: P1
effort: "L"
dependencies: [2, 5, 7, 12]
---

# Phase 15: Email Intake

## Goal

A raw email posted by the adapter to the signed inbound endpoint for a verified Tenant domain is parsed, routed to the right
queue, deduplicated and stored as a ticket or a message, with its attachments.

## Context

The endpoint sits outside any Tenant host and outside user authentication, so it is the most exposed route in the app. Its
tenant comes only from the recipient domain matched to a verified `helpdesk_domains` row. Parse library and receiving mechanism
come from the phase 2 decision; this phase sees only the signed JSON envelope from `contracts/helpdesk/README.md`. Threading,
loop protection and HTML sanitising are phase 16, so this phase stores plain parsed text and creates a ticket for every accepted
message.

## Files to create

- Migration `V11__domains_and_inbound.sql`: `helpdesk_domains` (tenant, `domain` unique across all tenants once verified,
  `verification_token`, `status` PENDING|VERIFIED|FAILED, `verified_at`, `last_checked_at`), `queue_addresses` (queue, local part),
  `inbound_mail` (tenant, `adapter_message_id`, `message_id`, `raw_size`, `auth_results`, `outcome`, `ticket_id`, `received_at`,
  unique `(adapter_message_id)`), RLS on tenant tables, jOOQ regenerated
- `helpdesk/contexts/channel/.../email/**`: `DomainVerificationUseCase` and `DnsTxtChecker` port, `InboundSignatureVerifier`,
  `InboundMailController`, `MimeParser` (the library from phase 2), `MailRouter`, `IntakeEmailUseCase`, `DomainRecheckJob`
- Tests: `InboundSignatureTest` (tamper, replay, wrong key id, expired timestamp), `MimeParserTest` against
  `contracts/helpdesk/inbound-mail-vectors/`, `MailRouterTest`, `InboundTenantIsolationTest`, `DomainVerificationTest`,
  `InboundDedupeTest`, `LargeMessageMemoryTest`

## Tasks

1. **Domains.** `POST /domains` returns a TXT name and value; `POST /domains/{id}/verify` checks DNS and marks `VERIFIED`; a
   domain verified by one Tenant cannot be claimed by another; a daily job re-checks and marks a lapsed one `FAILED` and stops
   routing for it. Needs `helpdesk:domain:manage` (not grantable to a Tenant System).
2. **Signature.** Verify `Helpdesk-Inbound-*` per the contract before reading the body into memory beyond the size limit;
   reject unknown key id, a timestamp outside five minutes and a bad signature with `401` and no detail.
3. **Parse.** Extract `From`, `Reply-To`, `To`, `Cc`, `Subject`, `Message-ID`, `In-Reply-To`, `References`, `Date`, text and
   HTML parts, attachments (stored through `AttachmentStore.putVerified`), charset-decoded; a message over the size limit or
   a malformed one is recorded with outcome `REJECTED_*` and answered `422`; nothing is mailed back.
4. **Route.** Pick the Tenant from the recipient domain, the queue from the matching `queue_addresses` local part (a plus
   token is ignored for routing), falling back to the Tenant's default queue; an address on a verified domain with no queue
   goes to the default queue.
5. **Create.** For this phase every accepted message creates a new `email` ticket with the requester found or created by the
   `From` address, the message `INBOUND`, the parsed attachments, and the recorded `senderVerification` from `authResults`
   (`VERIFIED` only when DMARC passes, `UNVERIFIED` otherwise, shown to agents, never used to trust identity).
6. **Idempotency.** The adapter message id is the dedupe key: a repeated post returns `200` with the first result and creates
   nothing.
7. **Backpressure.** A per-adapter rate cap and a bounded queue of in-flight parses; over the cap answers `503` with
   `Retry-After` so the adapter retries.

## Verification

- Each vector in `contracts/helpdesk/inbound-mail-vectors/` parses to its expected JSON.
- Mail for tenant A's verified domain never creates anything for tenant B; a domain claimed by A cannot be verified by B.
- A bad, replayed or stale signature is `401`; a body over the limit is refused before it is buffered fully.
- The same adapter message id twice creates one ticket.
- A 25 MB message is parsed within the memory budget set in the test, and a malformed MIME does not crash a worker.

## Risks

- The endpoint is a public attack surface: the signature check precedes everything and a deployment restricts its network
  (phase 17 and `docs/helpdesk.md`).
- The parse library's behaviour on malformed MIME is the real risk; the vectors include broken boundaries, nested multipart and
  a non-UTF-8 charset.
