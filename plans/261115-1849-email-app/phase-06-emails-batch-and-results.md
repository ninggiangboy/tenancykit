---
phase: 6
title: "Single and batch email, results, view email"
status: pending
priority: P1
effort: "L"
dependencies: [2, 4, 5]
---

# Phase 6: Single and Batch Email, Results, View Email

## Goal

`POST /emails` and `POST /emails/batch` render a template for a recipient, store the rendered email, hand it to mailer
through the outbox, and update the email's status when mailer reports the outcome. `GET /emails/{id}` shows it.

## Data model (context `sending`)

- `emails(id, tenant_id, kind, campaign_id?, receiver_id?, to_address, from_key, from_name?, reply_to?, subject, template_id?, template_revision?, status, status_reason?, request_id, body_html?, body_text?, body_purged_at?, delivery_updated_at?, created_at, updated_at, unique (tenant_id, request_id))`
  - `kind`: `TRANSACTIONAL` | `MARKETING`
  - `status`: `QUEUED` → `SENT` | `FAILED`; `SKIPPED` for an eligibility refusal (never published); `reason` explains it
  - Index `(tenant_id, status, created_at)` and `(tenant_id, campaign_id)`.
- `email_events(id, tenant_id, email_id, campaign_id?, type, link_id?, occurred_at)` — insert-only; `type` is
  `SENT` | `FAILED` here and `OPENED` | `CLICKED` | `UNSUBSCRIBED` in phase 8. No IP or user agent is stored.
- RLS on both tables.

## Flow

1. Request → validate → resolve sender (`email_settings` default or the request's `from_key`; the allowed keys are mailer's).
2. For each item: `SendEligibility.check(tenant, address, kind)`; if refused, store `SKIPPED` and return it. Otherwise
   render through `content`'s `RenderTemplate` port (or accept inline `subject`/`html`/`text` with the same limits).
3. In **one transaction**: insert the `emails` row (`QUEUED`, `request_id = id`) and append the `email.send` command to the
   outbox with `to = [address]`, the stored `from_key`, and (marketing only, phase 8) `headers`.
4. The relay publishes; mailer delivers; the `email.mailer-events` consumer applies `email-delivery.changed`.

## Result consumer

- Inbox-deduplicated like every consumer; the handler consumer name is asserted in a test.
- Matches by `(tenant_id, requestId)`. Unknown request id: log and drop (not an error: the email may have been purged).
- Applies only if `completedAt` is newer than `delivery_updated_at`; `SUCCEEDED` → `SENT`, `DEAD` → `FAILED` with
  `lastError`; unknown `status` values are ignored. Appends a `SENT`/`FAILED` event.

## Endpoints

`POST /emails` (`Idempotency-Key` header becomes `request_id`'s namespace so a retried call returns the same email),
`POST /emails/batch` (≤ 100 items, each with its own recipient, template and variables; the response lists a per-item
result and never fails the whole call for one bad item), `GET /emails/{id}` (status, reason, events, rendered subject/html/text
until purged), `GET /emails` (filter by status, kind, campaign, recipient; cursor paging). Permissions `email:email:send`
and `email:email:read`.

## Files to create

- `email/contexts/sending/src/{domain,application,adapter}/...` (email aggregate, send and batch use cases, result handler,
  outbox publisher adapter, jOOQ repositories, controllers)
- `email/apps/app-boot/src/main/resources/db/migration/V4__emails.sql`; `contracts/openapi/email.yaml` (emails)
- Tests under `email/apps/app-boot/src/test/java/dev/ngb/email/sending/`

## Verification

- Contract test: every published command validates against `contracts/commands/email.send.schema.json`.
- Integration (Postgres + Rabbit + real mailer container or an in-test consumer): send → `QUEUED` → result event →
  `SENT`; a rejected send → `FAILED`; a replayed result event changes nothing; an older `completedAt` is ignored.
- A retried `POST /emails` with the same `Idempotency-Key` creates one row and one outbox row.
- A suppressed address returns `SKIPPED` and writes **no** outbox row.
- A render failure stores `FAILED` with a reason and writes no outbox row.
- RLS and API cross-tenant tests; no address or body in logs (log-capture test).

## Risks

- Dual write: the `emails` insert and the outbox append must share the transaction; a test kills the process between
  relay publish and mark and asserts a republish does not create a second mailer delivery (mailer's ledger key).
- `body_*` columns hold personal data: they are the first thing the purge job (phase 9) removes.
