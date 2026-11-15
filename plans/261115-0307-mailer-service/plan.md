---
title: "Mailer service — outbound email gateway"
description: "A standalone service that consumes email.send commands over AMQP and delivers already-rendered email through SES, with a per-recipient delivery ledger and per-tenant or shared SES credentials."
status: pending
priority: P1
effort: "10 × M/L"
branch: dev
tags: [feature, backend, infra, messaging, email]
blockedBy: []
blocks: []
created: 2026-11-15
---

# Mailer Service — Outbound Email Gateway

## Overview

`mailer` is a new standalone service (`dev.ngb.mailer`) that owns exactly one thing: taking an
already-rendered email request off a queue and delivering it through Amazon SES reliably, with a record of
what happened. It does not choose recipients, render templates, or interpret business events. Producers
send a `email.send` command carrying the finished subject, HTML and text.

Today the suite has no email capability at all. `billing.events` has a producer and zero consumers, and the
transactional outbox, broker relay, topology, inbound dispatcher and inbox dedupe are already implemented and
in use. This plan adds only the missing piece: an SES adapter, a delivery ledger, and the service shell
around them.

## Goals

| # | Goal | Priority |
|---|------|----------|
| 1 | A command on `mailer.commands` becomes one email through SES, with at-least-once delivery | P1 |
| 2 | Every delivery is answerable: which recipient, which attempt, which status, why it failed | P1 |
| 3 | A repeated command never sends twice | P1 |
| 4 | A tenant can bring its own AWS account (cross-account role) or use the suite's shared identity | P1 |
| 5 | Local development runs the full pipeline against Mailpit without AWS | P1 |
| 6 | No cross-service coupling: mailer reads no other service's database or exchange | P1 |

## Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | [Email Command Contract](./phase-01-email-command-contract.md) | Pending |
| 2 | [Service Skeleton and Delivery Domain](./phase-02-service-skeleton.md) | Pending |
| 3 | [Name Registration and Architecture Gates](./phase-03-name-registration.md) | Pending |
| 4 | [Persistence and Delivery Ledger](./phase-04-persistence-and-ledger.md) | Pending |
| 5 | [Local Ops and Mailpit](./phase-05-local-ops.md) | Pending |
| 6 | [Command Consumer](./phase-06-command-consumer.md) | Pending |
| 7 | [SMTP Sender and Delivery Job](./phase-07-smtp-sender-and-delivery-job.md) | Pending |
| 8 | [SES Adapter and Shared Mode](./phase-08-ses-adapter-and-shared-mode.md) | Pending |
| 9 | [Tenant Credentials and Assume-Role](./phase-09-tenant-credentials-and-assume-role.md) | Pending |
| 10 | [AWS Infrastructure as Code, Scripts and Docs](./phase-10-aws-deploy-and-docs.md) | Pending |

Numbers in the `effort` field are relative size, not duration: S is a focused change, M is a phase with a
new subsystem, L is a phase that also touches infrastructure.

## Key Decisions

These were settled before planning and are not re-opened by any phase:

| Decision | Choice | Why |
|---|---|---|
| Scope of the service | Delivery only; no recipient resolution, rendering, i18n or campaign logic | Keeps business decisions with the apps that own them |
| Input channel | AMQP command `email.send`; no HTTP API in V1 | Matches the suite's existing transport; no new auth surface |
| Producer wiring | Out of scope; V1 defines the contract and consumes it | Removes a cross-service change from the critical path |
| Content | Producer sends rendered `subject`/`html`/`text` | Avoids a template engine, per-tenant overrides and locale fallback |
| State | Full delivery ledger with attempts | The only way to answer "was this email sent" |
| Retry driver | The ledger, not AMQP retries | Handler never calls SES, so transport retry cannot duplicate a send |
| Runtime | Separate `migrate` and `worker` profiles | Migrations run once, not per instance |
| Sender | Two adapters behind one port: SMTP (local) and SESv2 (prod) | STS credentials cannot authenticate over SMTP, so a single SMTP adapter cannot serve both |
| SES credentials | `SHARED` (suite identity) or `TENANT` (cross-account role, `ExternalId` required) | Avoids storing long-lived tenant AWS secrets |

## Extension Trace

Two variants are real today, not hypothetical, and each gets a boundary because of that:

- **Two delivery transports** (SMTP for local, SESv2 for production). They share nothing at the wire level —
  different protocols, different credentials, different error vocabularies. The stable contract is the
  `EmailSender` port, which returns a closed result of `Accepted(messageId)` / `Rejected(reason)` /
  `Transient(reason)`. Shared behaviour (ledger, retry, backoff) stays in the application layer; only the
  send call and its error translation live in an adapter.
- **Two credential modes** (`SHARED`, `TENANT`). They differ in capability: only `TENANT` needs
  `sts:AssumeRole` and a region per row; only `SHARED` can rely on the task role's ambient credentials. The
  boundary is a `CredentialResolver` that returns `(region, AwsCredentialsProvider, fromAddress)`, so the
  delivery job is unaware of which mode it is using.

What this plan deliberately does **not** build: a provider registry, a plugin loader, a channel abstraction
covering SMS or push, or a factory selecting a credential source by string. Those have no second real case
here, and their reading and maintenance cost exceeds a future refactor. A third transport, if it ever
arrives, adds one adapter and one enum value.

## Non-Goals

- Deciding who is notified, reading `customers.email`, or interpreting `invoice.created`.
- Rendering templates, i18n, per-tenant template overrides.
- In-app notifications, push, SMS, Slack or Telegram.
- An HTTP API for status, templates or credential management.
- A real producer in Billing or Platform.
- Bounce and complaint handling, attachments, bulk/marketing features.
- Load-balancing across several SES credentials. SES quota is per account and region, so several keys in
  one account buy nothing; per-tenant mode already gives each tenant its own quota.

## Cross-Plan Dependencies

| Relationship | Plan | Status |
|-------------|------|--------|
| Independent | `261112-0726-enterprise-suite-foundation` | Phase 10 (`email-and-templates`) was cancelled; this plan supersedes it with a delivery-only scope |

## Success Criteria

- [ ] `cd mailer && ./gradlew build` passes, including ArchUnit, Spring Modulith and the jOOQ drift test.
- [ ] A message on `mailer.commands` produces exactly one email in Mailpit and one `SUCCEEDED` ledger row.
- [ ] Replaying the same message id produces no second email.
- [ ] With Mailpit stopped, the row retries with backoff and reaches `DEAD` at the configured budget; restarting Mailpit lets a fresh request succeed.
- [ ] `ops/smoke/isolation.sh` passes with `mailer` in the matrix, and `mailer` is refused when publishing to `platform.events` or `billing.events`.
- [ ] No class under `dev.ngb.mailer` references another service's package.

## Corrections Found After Red Team

Two facts were wrong in the reviewed draft and were corrected afterwards:

- **SESv2 has no `GetSendQuota` operation.** The action list contains `GetAccount`, whose response carries
  `SendQuota{Max24HourSend, MaxSendRate, SentLast24Hours}`; `GetSendQuota` exists only in SES v1. Phases 9
  and 10 were corrected, and the IAM action is `ses:GetAccount` (`sesv2:` is the SDK/ARN name, never an IAM
  action prefix).
- **Use AWS Emulator, but it is a smoke test, not a proof.** The official emulator
  (`docs.aws.amazon.com/emulator`, CC0-1.0, free) is the right local/CI tool: it has one crate per service
  generated from the published API model and returns HTTP 501 `NotImplemented` for anything it lacks, so a
  gap fails loudly instead of lying. It also states its own limits, and they hit this plan directly:
  *"Don't use it for production workloads or to test security"* because it does not evaluate IAM policies,
  and *"one Region and one account"* because it does not separate resources by account. So it cannot prove
  the tenant trust policy or the `sts:ExternalId` requirement, which is the entire security core of `TENANT`
  mode — that needs a real account. It also serves self-signed HTTPS on `127.0.0.1:9297`, which the Java SDK
  needs a trust store for, and telemetry is on by default (`EMULATOR_TELEMETRY=off`). Phase 8 therefore uses
  it for the happy path only, adds a WireMock wire-contract test for every error branch (an emulator will not
  inject 429/403 on demand), and phase 10 proves the AWS contract with the mailbox simulator
  (`success@`, `bounce@`, `complaint@`, `ooto@`, `suppressionlist@simulator.amazonses.com`), which works
  **inside the sandbox** and so does not need production access to be useful.

## Known Gaps Accepted by This Plan

- **Under `TENANT` mode, `SUCCEEDED` means SES accepted the message, not that it reached a mailbox.** Bounce
  and complaint events are recorded in the tenant's account. A tenant can forward them by allowing our
  account to subscribe to its SNS topic (its topic policy must permit `sns:Subscribe` from
  `arn:aws:iam::<our-account>:root`; our queue policy must permit `sns.amazonaws.com`), but that work is not
  in this plan.
- **The envelope's `tenantId` is asserted by the publisher, not authenticated.** `AmqpInboundDispatcher`
  binds the transaction scope straight from `event.tenantId()`
  (`core/core-messaging/.../inbound/AmqpInboundDispatcher.java:58-60`), and broker users are per service, never
  per tenant. So anything holding `write` on `mailer.commands` — today the mailer service user itself, later
  whatever producer we grant it to — can name any tenant, and under `TENANT` mode can therefore send from any
  tenant's verified domain. The controls are the per-service broker user, the credential-account allowlist in
  phase 10, and rejection of the platform tenant id on this path. A stronger design (per-tenant routing keys
  or an HMAC on the envelope) is deliberately not built, because it needs a producer to exist first. Recorded
  in `contracts/commands/README.md` so a producer author sees it.
- **A worker crash between the SES accept and the ledger write resends on the next claim.** Inherent to
  at-least-once; `requestId` idempotency limits the damage but cannot remove it.
- **`CredentialAdminUseCase` has no HTTP surface in V1.** Credentials are registered by ops. Until an admin
  API exists, the table's writers are a human and a probe script.

## Sequencing Blocks

Phases 1–9 are self-contained and verifiable on a laptop: 1–7 through Mailpit, 8 through the WireMock wire
contract plus the AWS Emulator, 9 through a stubbed STS/SES. **Phase 10 is written as infrastructure as code
only** — `fmt`, `init -backend=false` and `validate`, all of which run with no AWS credentials, which is what
`.github/workflows/terraform.yml` already does.

So the whole plan can be completed before the application goes live. What remains afterwards is a go-live
sequence, not a planning gap:

| Go-live step | Blocked on |
|---|---|
| `terraform apply` | An AWS account and a decision on the sending domain |
| SES sending | Production access (account-level request, up to 96 h) and three published DKIM CNAMEs |
| `run_once rds_init` / `mq_users` | The applied stack; note `rds-init` fails on an existing role by design |
| `TENANT` credential mode being usable | At least one tenant with its own AWS account and a verified domain in it |
| Proving the AWS contract | Nothing — the mailbox simulator works inside the sandbox; do it before requesting production access |

**If the work must be cut, cut from phase 8 downward.** Phases 1–7 produce a service that delivers mail
correctly through SMTP, with a full ledger, dedupe, retries and a purge — the whole of the plan's stated
outcome except the production transport.

## Red Team Review

Four reviewers ran adversarial passes (assumption destruction, failure modes, security, scope) and every
finding was checked against the source before being applied. Findings that survived:

| Severity | Finding | Applied |
|---|---|---|
| Critical | A `@Scheduled` job has no `RequestScope`; `ScopedTenantContext.current()` throws, so nothing would ever deliver | Yes — added `mailer_tenants` (phase 4) and `TenantSweep` (phase 7) |
| Critical | `check ((status='SUCCEEDED') = (completed_at is not null))` makes `DEAD` unwritable, so permanent rejections loop forever | Yes — Billing's predicate including `DEAD`, plus psql proofs (phase 4) |
| Critical | `EnvelopeCodec`, `Relay` and `Document(2)` were missing from the dispatcher wiring; the context could not start | Yes — full six-argument wiring (phase 6) |
| Critical | "Throw a non-retryable exception" is impossible: `handle` declares no checked exception and `defaultRetry()` retries every `RuntimeException` | Yes — two workable options stated, with the requeue-forever trap named (phase 6) |
| Critical | RLS was never enabled on `mailer_credentials` or `email_bodies` | Yes — `enable_tenant_rls` on every tenant-owned table, plus an RLS test as the app role (phase 4) |
| Critical | Copying the tenant's `ArchitectureTest` cannot go green: it hardcodes a tenant class and `>= 10` use cases | Yes — rebuilt from Billing's set with mailer's real values, and the runtime rules added (phase 2) |
| Critical | `event_inbox` was specified with three columns; the core's published contract requires `processed_at` | Yes (phase 4) |
| Critical | `fromName`, `replyTo` and `subject` were unconstrained producer input reaching mail clients | Yes — schema patterns plus value-object validation and a reply-to allowlist (phases 1, 8) |
| High | `attempts` incremented in the outcome, so a crash loop never exhausts the budget; claim omitted `IN_PROGRESS` | Yes — increment in the claim, CAS on the outcome, re-claim `IN_PROGRESS` (phase 7) |
| High | Infinite JavaMail timeouts with no lease/timeout invariant | Yes — explicit timeouts and a construction-time check (phase 7) |
| High | A duplicate `requestId` under a new event id is silently dropped, so a `DEAD` row can never be re-requested | Yes — status-aware duplicate policy (phase 6) |
| High | No housekeeping job: bodies and inbox grow forever, contradicting the retention claim | Yes — `HousekeepingJob` with bounded deletes (phase 7) |
| High | Rotation impossible under the partial unique index; provider/client caches never invalidated | Yes — version-keyed cache with eviction, `(tenant_id, from_key, version)` (phases 4, 9) |
| High | `sts:ExternalId = "*"` under `StringLike` is a presence check, not a value check; account was `*` | Yes — account allowlist and the correct location for the value check documented (phase 10) |
| High | Master key was to be injected from a secret map readable by five other workloads | Yes — narrow the secrets policy; dedicated KMS noted as the better answer (phase 10) |
| High | Dockerfile based on the tenant's would omit `contracts/`, so the schema is missing only in the image | Yes — base it on Billing's (phase 5) |
| High | Broker regex single-escaped would abort `rabbitmq-users` and break every `compose up` | Yes — double escaping and the write/read set corrected (phase 5) |
| High | The isolation gate cannot fire on an external coordinate; the "prove it fails" step proved nothing | Yes — group axis added everywhere, module axis kept, proof retargeted (phases 2, 3) |
| High | `docs/suite-messaging.md` and `docs/README.md` were never updated | Yes (phases 1, 10) |
| Medium | Two `@Configuration` classes would both declare `clock`/`idGenerator` | Yes — one wiring class (phases 2, 6) |
| Medium | Gating the worker on Mailpit health contradicts the ledger design and the image has no shell to probe with | Yes — `service_started`, and `management.health.mail.enabled: false` (phase 5) |

Rejected findings, and why:

- *"End V1 at phase 7 and move 8–10 to a follow-up plan."* The requester explicitly asked for SES deployment
  and per-tenant credential modes. The sequencing risk is real, so it is recorded under Sequencing Blocks and
  phases 1–7 stay independently shippable; the scope is not cut.
- *"Cut phase 9 / `TENANT` mode."* Same reason — it is the requested feature, not a speculative one. The
  duplication it originally carried (a third `fromKey` lookup) was real and is fixed.
- *"Delete `DeliveryPolicy` and use `MessagingProperties.Relay`."* Partially accepted: the field vocabulary and
  jittered backoff are aligned, but `DeliveryPolicy` stays a domain object because the email channel has a
  third outcome (`Rejected` → immediate terminal) that the relay's publish model does not have.
- *"Edit `ArchitectureRulesTest` to register the new service."* Dropped. `core/core-arch/.../ArchitectureRulesTest.java:58-66`
  compares one pure function across profiles and cannot detect a missing service.

### Whole-Plan Consistency Sweep

After applying the findings, the plan files were re-read end to end. The following were reconciled:
phase 2 no longer creates `SystemConfiguration`; phase 6 is the sole owner of `clock`/`idGenerator`;
`EmailSendCommand` moved from `api` to `application/.../command`; phases 7→8→9 now extend one
`SenderResolver` instead of writing the lookup three times; the `event_inbox` shape, the `completed_at`
predicate, the broker regex and the Dockerfile base are consistent between the phase that creates each and
the phase that verifies it; and `mailer.commands` appears in both `contracts/messaging/README.md` (phase 1) and
`docs/suite-messaging.md` (phase 10). No unresolved contradiction remains.

### Validation Log

Not run as a separate interview: every decision that would have been asked was settled before planning
(input channel, content ownership, ledger depth, runtime split, credential mode, sender), and the red-team
pass replaced the verification step by checking each claim against the source. Open items that remain are
external, not design choices, and are listed under Sequencing Blocks and Known Gaps.
