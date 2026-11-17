---
phase: 13
title: "Public form and tracking link"
status: pending
priority: P1
effort: "L"
dependencies: [9, 12]
---

# Phase 13: Public Form and Tracking Link

## Goal

A Tenant publishes a support form with its own fields; a customer submits it without logging in, receives a confirmation mail,
and can follow the ticket and reply through a tracking link, all behind bot and abuse protection.

## Context

Copy booking's public-gate shape (`plans/261116-2239-booking-app/phase-13-public-gate.md`): an HMAC-signed rotating opaque token
(not a JWT), a guard filter before authentication (rate and body limits), a per-instance token bucket keyed by an HMAC of the
client IP with a daily-rotated secret, a honeypot, permit-all `/p/**`, Tenant from the host. The form is the only place helpdesk
sends mail without an agent, so it is also the only mail-bombing risk.

## Files to create

- Migration `V9__forms.sql`: `forms` (tenant, `slug`, `title`, `fields` JSON validated, `queue_id`, `priority`, `enabled`,
  `daily_cap`), `form_submissions` (idempotency and the confirmation ledger), `public_limits`, RLS, jOOQ regenerated
- `helpdesk/contexts/channel/.../form/**`: `FormDefinition` (typed fields: text, long text, email, phone, select, checkbox,
  file), `SubmitFormUseCase`, `TrackingTokens`, `PublicGuardFilter`, `PublicRateLimiter`, `Honeypot`, `PublicFormController`,
  `PublicTrackingController`, `FormController` (`helpdesk:form:manage`)
- Tests: `FormValidationTest`, `PublicGuardTest`, `SubmitFormIntegrationTest`, `ConfirmationMailTest`,
  `TrackingTokenTest`, `PublicLeakTest`, `FormRowLevelSecurityTest`

## Tasks

1. **Definition.** A form has exactly one email field marked `requester`, up to 20 fields of the typed set, a slug unique per
   Tenant, and a destination queue; invalid definitions are refused when saved.
2. **Submit.** `POST /p/forms/{slug}` validates every field, applies the guard order (rate limit, body limit, honeypot, host
   Tenant), creates or finds the requester by email, creates a ticket in channel `form` whose first message is the answers as a
   labelled text, with attachments uploaded through a public presign limited to this form; it answers with a ticket number
   and nothing else.
3. **Confirmation.** One `email.send` to the requester with the number, a short text and the **tracking link**; not sent
   again to an address confirmed within the last hour, capped per Tenant per day (`daily_cap`) and per client fingerprint, so
   the form cannot be used to mail a victim repeatedly.
4. **Tracking link.** `GET /p/t/{token}` returns the ticket's public messages only (never notes, never agent identity beyond a
   display name), its status and the allowed attachments as presigned downloads; `POST /p/t/{token}` adds a reply as the
   requester through the shared intake policy. The token binds tenant and ticket, is HMAC signed with a key id, has a long
   lifetime but is revocable by closing the ticket's link (a Tenant setting), and a token for another Tenant's host is refused.
5. **Hygiene.** No personal data in logs (fingerprints only); `Referrer-Policy: no-referrer`, `Cache-Control: no-store` on tracking
   responses; the same error shape for a missing, expired or foreign token.

## Verification

- A submission creates one ticket and one confirmation command; a repeat with the same address inside the hour sends no second
  mail but still creates the ticket.
- The 121st request per minute from one fingerprint is `429`; a filled honeypot, an oversize body and an invalid field are
  refused; the daily cap stops confirmation mail and still accepts tickets.
- The tracking view of a ticket with a note and an agent reply shows the reply only; a crafted token for another ticket or
  Tenant is refused with the same code as a missing one.
- A reply through the tracking link reopens a solved ticket and appears as an `INBOUND` message.

## Risks

- A form with a file field is a storage abuse vector: public presign is size-capped, rate limited and the pending-upload purge
  from phase 7 applies.
- Rate limiting is per instance (as booking's): the effective limit is N times the setting, documented.
