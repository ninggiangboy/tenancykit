---
title: planned-the-mailer-email-gateway-service
date: 2026-11-15
summary: Ten-phase plan for a standalone outbound email service consuming an email.send AMQP command and delivering through SES with a per-recipient ledger.
---

# planned-the-mailer-email-gateway-service

## What happened

Wrote `plans/261115-0307-mailer-service/`: `plan.md` plus ten phase files (85 checkboxes) for a new
`dev.ngb.mailer` service. It consumes an `email.send` AMQP command carrying already-rendered content and
delivers it through SES, with a per-recipient delivery ledger, inbox dedupe, ledger-driven retry and a body
TTL purge. The scope was settled before planning through an advisory interview: delivery only, no recipient
resolution, no template rendering, no HTTP API, no producer in V1.

Two research findings changed the design. AWS SDK v2 (>= 2.17.0) shades Jackson into
`software.amazon.awssdk.thirdparty.jackson.core` with `createDependencyReducedPom`, so it does not collide
with this repo's Jackson 3 (`tools.jackson`) and needs no exclusion — the suspected build conflict was not
real. SES SMTP credentials cannot be derived from temporary STS credentials (AWS documents this explicitly),
so a cross-account assume-role design cannot use SMTP at all: two sender adapters are mandatory, not a
stylistic choice.

## Decision

Four reviewer passes (assumption destruction, failure modes, security, scope) attacked the first draft and
every finding was checked against source before being applied. Four were critical and would have made the
plan fail outright:

1. **No tenant scope in scheduled jobs.** `ScopedTenantContext.current()` throws when nothing is bound
   (`core/core-spring/.../tenancy/ScopedTenantContext.java:16-19`), and Billing's jobs work only because
   `TenantSweep` wraps each in `runAs(RequestScope.tenant(id))` over a replica mailer does not have. Added a
   `mailer_tenants` projection and a sweep; without this the service would have logged a stack trace every
   poll and delivered nothing.
2. **`completed_at` constraint made `DEAD` unwritable.** The draft used
   `(status = 'SUCCEEDED') = (completed_at is not null)`; Billing's webhook table uses
   `status in ('SUCCEEDED','DEAD')` (`V7__webhooks.sql:56`). The draft constraint would have turned every
   permanent rejection into an infinite resend loop.
3. **Incomplete dispatcher wiring.** `AmqpInboundDispatcher` takes six arguments including an `EnvelopeCodec`
   that no bean provides, and `MessagingProperties` requires `relay` and `document`, with the envelope
   version fixed at 2.
4. **Row-level security was omitted on `mailer_credentials` and `email_bodies`** while the plan claimed RLS
   was the isolation guarantee.

Also applied: `attempts` must be incremented in the claim with a compare-and-swap on the outcome (a crash
window otherwise never exhausts the retry budget); the claim must reselect `IN_PROGRESS` rows; the isolation
gate needs a dependency-**group** axis because a sibling build is never a `ProjectDependency`; the copied
`ArchitectureTest` cannot go green (the tenant's asserts a tenant class and `>= 10` use cases); `event_inbox`
needs `processed_at` per `core-messaging/schema-compat.md`; the Dockerfile must copy `contracts/` or the
schema is missing only in the image; and `sts:ExternalId = "*"` under `StringLike` is a presence check, not a
value check, so the role-account allowlist carries the real restriction.

Scope-reduction findings were rejected: the requester asked for SES deployment and per-tenant credential
modes, so phases 8-10 stay, with their external prerequisites recorded as sequencing blocks rather than
cut.

## Next steps

Phases 1-7 are self-contained and verifiable on a laptop; they deliver the whole stated outcome except the
production transport. Phase 8 needs an AWS account and a verified SES identity; phase 9 needs a tenant with
its own AWS account; phase 10 needs SES production access (account-level, up to 96 h) and three
hand-published DKIM CNAMEs. Start with `/ak-cook plans/261115-0307-mailer-service/plan.md`.

Known gap kept open deliberately: the envelope's `tenantId` is asserted by the publisher, not authenticated,
so anything with `write` on `mailer.commands` can name any tenant. Recorded in `contracts/commands/README.md`
and in the plan's Known Gaps rather than half-solved.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
