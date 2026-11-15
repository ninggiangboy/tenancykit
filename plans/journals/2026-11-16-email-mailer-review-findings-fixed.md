---
title: Email/mailer — fixed the review's high and medium findings
date: 2026-11-16
summary: "Tracking paths behind the ALB prefix, the unsubscribe link, an exhausted delivery's missing result event, per-campaign tick isolation, type-safe segment SQL, escaped redirect URLs, batch item keys and kind failures, text-only bodies, and a tracking key with no public default"
---

# Email/mailer — fixed the review's high and medium findings

## Outcome

Repaired the review's findings in its recommended order, each with a test that fails without the fix. The `mailroom`
rename was left for a separate change, so this diff is only about behaviour.

### 3 — a delivery killed by the sweep reported nothing
`killExhausted` set `DEAD` and returned a count, so a delivery whose worker died on its final attempt never emitted
`email-delivery.changed` and the producer's email stayed `QUEUED` for ever. It now returns the killed rows
(`ExhaustedDelivery`), and `DeliverEmailsUseCase` publishes their `DEAD` result in the sweep's own transaction.

### 2 — the unsubscribe link was rewritten as a click
`MarketingTracking` matched every `http(s)` `href`. The body's unsubscribe link is now compared against the signed
unsubscribe URL and left alone, so it keeps working after housekeeping purges `email_links` and an unsubscribe is never
counted as a click.

### 4 — one bad row blocked the whole tick
`PublishCampaignBatchUseCase` and `AdvanceCampaignsUseCase` each ran one transaction per tick. Now each campaign starts
in its own transaction and each recipient is claimed, sent and marked in its own. A campaign whose segment was deleted
is logged and left `SCHEDULED`; a recipient whose own transaction fails keeps its claim and returns when the lease
expires, while the rows after it still publish. The segment validator also refuses an ordering operator on a built-in
text field, which the compiler could never have executed.

### 8 — the rate limit was per campaign, not per tenant
The per-tick budget is computed once and spent across every `SENDING` campaign, so N campaigns no longer allow N times
the ceiling. The tick count is derived from `email.campaign.publisher-interval` rather than a hardcoded 12, so changing
the interval cannot silently change the spend per minute.

### 5 — a mixed-type attribute broke the segment query
Equality, membership and non-equality now compile to JSONB containment
(`attributes @> jsonb_build_object(key, to_jsonb(value))`), which is type-exact and is what the `jsonb_path_ops` GIN
index answers. Ordering compares a number behind `jsonb_typeof(attributes -> key) = 'number'`, so a receiver holding
`age = "unknown"` no longer aborts a preview or a snapshot. Verified with a real container: `@>` takes a Bitmap Index
Scan, the previous `->>` shape takes a sequential scan, and the guarded cast skips the string row.

### 1 — tracking 404'd behind the ALB
The ALB cannot strip `/email`, so `/email/t/...` reached a controller mapped at `/t`. The controller now maps both
spellings, and the security chain and both suite filters recognise both, so a pixel, click and unsubscribe work in
compose and on ECS.

### 6, 7, 9, 10 — the medium findings
- Escaped `&amp;` is unescaped before the URL is recorded, and a stored URL that cannot be a `Location` answers the
  "link expired" page instead of a 500. A destination the link column cannot hold is left untracked rather than
  aborting the send or the tick.
- A batch item may carry its own `idempotencyKey`; an item whose `kind` is missing or unknown is reported at its index
  instead of failing the whole call. An over-long idempotency key is refused rather than silently truncated into a
  collision.
- A text-only inline body renders a null HTML part instead of throwing.
- `EMAIL_TRACKING_SECRET` has no default: a blank key fails startup.

## Verification

- `cd email && ./gradlew build` — green, 165 tests.
- `cd mailer && ./gradlew build` — green, 127 tests.
- New tests: `MarketingTrackingTest` (6), `JooqSegmentMatcherIntegrationTest` (4, real Postgres),
  `PublishCampaignBatchUseCaseTest` (5), `AdvanceCampaignsUseCaseTest` (3), `PublicTrackingControllerTest` (6 over both
  prefixes), plus a text-only renderer case, segment-validator cases, and
  `DeliveryRetryIntegrationTest#aWorkerLostOnItsFinalAttemptStillReportsTheOutcome` on the real database.

## Limitations

- Terraform, compose and Caddy were not applied; the ALB claim is reasoned from `alb.tf` and proved only by the
  controller test, not by a live ALB.
- The complementary design risk — marketing mail through the suite's shared SES identity with no per-tenant daily cap
  and no bounce/complaint handling — was left as the reviewer framed it: a decision for the owner, not a defect fixed
  here.
