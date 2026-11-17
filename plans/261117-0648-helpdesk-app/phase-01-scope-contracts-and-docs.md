---
phase: 1
title: "Scope, contracts and docs"
status: pending
priority: P1
effort: "M"
dependencies: []
---

# Phase 1: Scope, Contracts and Docs

## Goal

The helpdesk's public shape is written down before any Java exists: documents state the new app and the reversed non-goal,
and the OpenAPI, permission catalog, event schemas and the two wire formats (inbound request signing, thread token) are
files a contract test can later check.

## Context

Follow the order booking used: contracts first, then code that must match them. Re-read each shared file just before editing it,
because other plans are in flight (`git status` shows uncommitted work in `attribution/` and `booking/`). Edits to shared files
are append-only except the two `docs/customer.md` lines the reversal requires.

## Files to create / modify

- Create: `docs/helpdesk.md` (what it is and is not, channels, status machine, threading, SLA, runtimes, broker grants, surface
  table, limits, the security model, the Tenant recipe for linking a ticket to a customer by `externalRef`)
- Modify: `docs/customer.md` (lines 19 and 232: replace the ticket non-goal with a pointer to `helpdesk.md`), `docs/README.md`
  (reading-order row), `docs/overview-and-scope.md` (dated reversal entry), `docs/architecture.md` (decision ledger row)
- Modify: `contracts/commands/email.send.schema.json` is **not** edited here; phase 11 owns it after its design gate.
- Modify: `contracts/messaging/README.md` (helpdesk declares no exchange, consumes `platform.events` and
  `mailer.events` on its own queues, produces `email.send`), `contracts/README.md`
- Create: `contracts/openapi/helpdesk.yaml` (every path in "Surface" below, with schemas and error codes)
- Create: `contracts/permissions/helpdesk.yaml`
- Create: `contracts/events/helpdesk/{ticket.created,ticket.replied,ticket.assigned,ticket.status-changed,sla.at-risk,sla.breached,ticket.merged,ticket.closed,requester.erased}.schema.json`
  and `contracts/events/helpdesk/README.md`
- Create: `contracts/helpdesk/README.md` (inbound request signing, the thread token, the inbound mail JSON envelope) and
  `contracts/helpdesk/inbound-mail-vectors/` (raw MIME samples with the expected parsed result, used by phases 15 to 17)

## Surface (what the OpenAPI covers)

- Tickets: `GET|POST /tickets`, `GET|PATCH /tickets/{id}` (ETag, `If-Match`), `GET|POST /tickets/{id}/messages`,
  `POST /tickets/{id}/{assign,status,merge}`, `GET /tickets/{id}/events`
- Attachments: `POST /attachments` (presign), `POST /attachments/{id}/complete`, `GET /attachments/{id}/download`
- Requesters: `GET /requesters/{id}`, `PATCH /requesters/{id}` (`externalRef`, name), `DELETE /requesters/{id}` (erasure, `202`)
- Configuration: `/queues`, `/groups`, `/canned-replies`, `/sla-policies`, `/business-hours`, `/forms`, `/channels`, `/domains`,
  `/webhook-endpoints`, `/settings`
- Channel push (Tenant System): `POST /channels/{channel}/messages`
- Public (no credentials): `GET|POST /p/forms/{slug}`, `GET|POST /p/t/{trackingToken}`
- Inbound (signed by the adapter, no user token): `POST /inbound/mail`

## Permissions (catalog)

`helpdesk:ticket:{read,create,reply,note,assign,status,merge}`, `helpdesk:channel:push`, `helpdesk:requester:erase`, `helpdesk:{queue,group,canned,sla,form,channel,webhook}:manage`
are grantable to a Tenant System; `helpdesk:settings:manage` and `helpdesk:domain:manage` are not (a person decides the sender
identity and which domain receives mail). Every code is tenant scope.

## Tasks

1. Write the documents and contracts above; every field and error code that a later phase implements appears in the OpenAPI.
2. Specify the thread token: `t<ticketNo>.<base32(hmac_sha256(key, tenantId|ticketNo)[0..5])>`, key id in a separate field of
   the settings so rotation keeps old keys for verification; the token goes into the local part after `+`.
3. Specify the inbound request signature: `Helpdesk-Inbound-Key-Id`, `Helpdesk-Inbound-Timestamp`, `Helpdesk-Inbound-Signature`
   (`v1=` hex HMAC-SHA256 of `timestamp.rawBody`), five-minute replay window, body limit 25 MB base64 or 35 MB raw.
4. Specify the inbound JSON envelope: `rawMimeBase64`, `envelopeFrom`, `envelopeTo[]`, `receivedAt`, `authResults`
   (`spf`, `dkim`, `dmarc` each `pass|fail|none`), `adapterMessageId` (dedupe key).
5. Write the nine event schemas (the eight webhook types plus `requester.erased`) with the same envelope fields as Survey and Booking events; `ticket.replied` carries
   `direction`, `channel`, `visibility`, `messageId` and a body excerpt, never an attachment byte.
6. Edit `docs/customer.md`, `docs/README.md`, `docs/overview-and-scope.md`, `docs/architecture.md`, `contracts/messaging/README.md`
   and `contracts/README.md`. Quote the reversal as a user decision of 2026-11-17.

## Verification

- `git grep -n -i "ticket" -- docs/customer.md` no longer shows a non-goal; it shows the pointer to `helpdesk.md`.
- Every schema under `contracts/events/helpdesk/` validates against the JSON Schema draft it declares; the OpenAPI passes the
  linter used by the other apps (`git grep -n "openapi" -- .github/workflows` for the command).
- The vectors directory has at least ten raw messages: plain, HTML, multipart with attachment, reply with token, reply with only
  `In-Reply-To`, auto-reply, bounce, list mail, forwarded, and a non-UTF-8 charset.
- `git diff --stat` touches only the files above.

## Risks

- A shared document changes between read and edit: re-read, reapply, never overwrite.
- The OpenAPI grows large; keep schemas in `components` and reuse the shared list query parameters from `contracts/openapi`.
