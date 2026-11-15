---
phase: 7
title: "Campaigns"
status: done
priority: P1
effort: "M"
dependencies: [6]
---

# Phase 7: Campaigns

## Goal

A Tenant creates a campaign for a group, sends it now or schedules it, can pause or cancel it, and the worker sends it in
rate-limited batches.

## State machine

```
DRAFT ──schedule──▶ SCHEDULED ──due or send-now──▶ SENDING ──all published──▶ COMPLETED
  │                    │  ▲                          │  ▲
  │                    │  └────── resume ─── PAUSED ◀┘  │
  └──────cancel────────┴──────────────────────────cancel┴──▶ CANCELLED
```

`COMPLETED` and `CANCELLED` are terminal. Pausing stops the worker from taking more batches; it never withdraws
commands already published.

## Data model (context `sending`)

- `campaigns(id, tenant_id, name, status, template_id, template_revision, target_type, target_id, from_key, from_name?, reply_to?, scheduled_at?, started_at?, completed_at?, version, created_at, updated_at)`
  - `target_type` is `GROUP` here; `SEGMENT` is added in phase 9 without a schema change (a check constraint, not an enum type).
  - The campaign **pins the template revision** when it leaves `DRAFT`.
- `campaign_recipients(tenant_id, campaign_id, receiver_id, email_id?, state, primary key (campaign_id, receiver_id))` —
  the **snapshot** of the audience taken once, in pages, when the campaign enters `SENDING`; `state` is `PENDING` |
  `PUBLISHED` | `SKIPPED`. A receiver added to the group later is not mailed; one who unsubscribes after the snapshot
  is re-checked at publish time.

## Worker behaviour

- A scheduler job (tenant sweep, like `mailer`'s) moves `SCHEDULED` rows whose `scheduled_at` is due to `SENDING`.
- While `SENDING`, each tick claims at most `campaign_publish_per_minute / ticks` `PENDING` recipients per tenant,
  re-checks `SendEligibility` (marketing), renders, writes the `emails` row and the outbox row in one transaction per
  email, and marks the recipient `PUBLISHED` or `SKIPPED`. When no `PENDING` remain the campaign becomes `COMPLETED`.
- Claiming uses `for update skip locked` with a lease, so two workers never take the same recipient, and a crash leaves
  the recipient claimable again. `requestId = emailId`, so a re-publish after a crash is idempotent in mailer.
- A render failure for one recipient (including a variable missing from that receiver's attributes, because rendering
  is strict) marks that email `FAILED` with the reason; it never stops the campaign and never sends a partial body.
  `stats.failed` counts it, and `GET /emails?campaign=…&status=FAILED` lists the receivers and reasons.

## Files to create

- `email/contexts/sending/.../campaign/*` (aggregate, transitions, create/update/schedule/send-now/pause/resume/cancel/test-send,
  snapshot builder, publisher tick)
- `email/apps/app-boot/.../jobs/CampaignSchedulerJob.java`, `CampaignPublisherJob.java`
- `email/apps/app-boot/src/main/resources/db/migration/V5__campaigns.sql`; OpenAPI additions
- Tests under `.../sending/campaign/`

## Endpoints

`POST /campaigns`, `GET /campaigns`, `GET /campaigns/{id}`, `PUT /campaigns/{id}` (DRAFT only, `If-Match`),
`POST /campaigns/{id}/schedule`, `POST /campaigns/{id}/send`, `POST /campaigns/{id}/pause|resume|cancel`,
`POST /campaigns/{id}/test` (render with sample data and send to up to 5 explicit addresses as `TRANSACTIONAL`, tagged
so they never count in stats), `GET /campaigns/{id}/stats` (sent counts here; opens and clicks arrive in phase 8).
Permissions `email:campaign:read|manage`.

## Verification

- State-machine unit test over every (state, action) pair, including illegal ones returning `409`.
- Integration: a campaign for 250 receivers publishes in several ticks within the cap; pause stops publishing mid-way;
  resume continues; cancel stops it; unsubscribing a recipient after the snapshot skips them.
- Two workers running concurrently publish each recipient exactly once.
- Crash test: kill the worker after the outbox write, before marking the recipient; the restart publishes no duplicate email.
- Test send is excluded from stats and never touches the snapshot.

## Risks

- A very large group: the snapshot is paged and bounded by a configured maximum audience size per campaign.
- Template deleted after scheduling: the pinned revision is immutable, so the campaign still renders.
