---
title: Email app — phases 4-10 delivered
date: 2026-11-15
summary: "Templates and the sandboxed renderer, audience and suppression, the send ledger and result consumer, campaigns, tracking and public endpoints, segments and housekeeping, and the ops seams"
---

# Email app — phases 4-10 delivered

## Outcome

Implemented the remaining phases of `plans/261115-1849-email-app` on top of the phase 1-3 delivery.

### Phase 4 — templates and the sandboxed renderer
- `content` context: immutable template revisions, the current pointer, ETag/If-Match updates, preview.
- JMustache wrapped in a sandbox: dotted variables are strict at every depth (including loop elements), partials and
  delimiter changes are refused, unescaped output is refused in subject/html, and a shared budget caps output size
  (262144), section depth (4), total iterations (10,000) and wall clock (500 ms).
- The renderer is unit-tested adversarially: nested loops, an oversize part, `{{> p}}`, `{{{x}}}`, a receiver attribute
  carrying `<script>`, a subject with a newline, and a randomized property loop that must terminate in bounds.

### Phase 5 — audience, suppression, unsubscribe
- `audience` context: receivers with normalized addresses and validated flat attributes, static groups and membership,
  suppressions with `MARKETING`/`ALL` scopes, and the eligibility truth table (transactional ignores a marketing
  suppression; `ALL` stops everything).
- Bulk upsert is per-item and idempotent; erasure removes the receiver and memberships but keeps the suppression; a
  suppression removal is the explicit re-subscribe.
- `SendEligibility`, `AudienceDirectory` and `UnsubscribeAddress` are the api seams other contexts use.

### Phase 6 — single and batch email, results
- `sending` context: the ledger, the `email.send` producer through the transactional outbox, and the
  `email-delivery.changed` consumer. `SUCCEEDED`→`SENT`, `DEAD`→`FAILED`, unknown statuses ignored, the newest
  `completedAt` wins.
- `Idempotency-Key` namespaces `requestId`; a suppressed address is stored `SKIPPED` with no outbox row; a render or
  command failure is stored `FAILED` with a safe reason and no outbox row.
- The produced command is validated against `contracts/commands/email.send.schema.json` before its outbox row exists.

### Phase 7 — campaigns
- The campaign state machine with 409 on illegal transitions; the audience is snapshotted once, in pages, when the
  campaign enters `SENDING`; eligibility is re-checked at publish time.
- The publisher claims with `for update skip locked` and a 60s lease, rate-limited per tenant. The email id is
  `UUID.nameUUIDFromBytes(campaignId:receiverId)`, so a crash after the outbox write republishes nothing.

### Phase 8 — tracking, public endpoints, statistics
- HMAC-SHA256 tokens (`version | keyId | tenant | email | link | action | mac`) with key-id rotation; the click
  endpoint redirects only to a URL recorded at render time; one-click unsubscribe writes a `MARKETING` suppression.
- Marketing mail gets a 1×1 pixel, rewritten links and `List-Unsubscribe`/`List-Unsubscribe-Post`; transactional mail
  is untouched. `{{unsubscribe_url}}` is available to every template.
- The public `/t/*` controller is small, `@PublicEndpoint`, and its tenant scope comes only from the verified token;
  the tenant and entitlement filters skip `/t/`.
- Campaign statistics aggregate live from the ledger and events.

### Phase 9 — segments and housekeeping
- A closed, typed JSON filter AST (depth 4, 20 predicates) compiled to parameterised SQL over `receivers.attributes`,
  always with the tenant, `SUBSCRIBED` and a suppression anti-join; preview returns a capped count and a sample.
- Housekeeping purges bodies and links after the tenant's retention, trims old campaign snapshots, and the inbox and
  dead outbox the way notification does.

### Phase 10 — ops, end to end, docs
- `email-migrate/api/worker` in compose, the Caddy `/email/*` route, the broker user's grants and the isolation smoke,
  the Keycloak `email-audience` mapper, the Terraform module and its ECS/RDS/MQ/secret wiring, and the CI workflow.
- `docs/email.md` gained the API surface, configuration, runbook and camelCase naming; `docs/runtime-profiles.md`,
  `docs/architecture.md`, `docs/suite-messaging.md`, `docs/mailer.md` and the root README list the app.
- The contract is now camelCase on the wire, matching billing and notification; the phase-1 skeleton's snake_case
  properties were the outlier.

## Evidence

- `cd email && ./gradlew build` — green, 137 tests.
- `EmailFlowIntegrationTest` runs the headline scenario against real PostgreSQL and RabbitMQ: template → receiver →
  group → campaign → published email with rewritten links and the unsubscribe header → mailer `SUCCEEDED` → an open and
  a click → one-click unsubscribe → statistics that match.
- `OpenApiContractTest` asserts the contract's operations and the controllers' paths are one set, and
  `PermissionCatalogTest` asserts every catalog code is enforced and every endpoint declares an access rule.
- `docker compose --env-file .env.example config` validates; the Keycloak realm export parses as JSON.

## Notable decisions and fixes

- **Reads need a unit of work.** Row-level security is set by the transaction hook, so a repository read outside a
  transaction sees no tenant. Every query class, every api service (`RenderTemplate`, `SendEligibility`,
  `AudienceDirectory`) and every pre-read inside a use case now runs inside a unit of work; a nested call joins its
  parent.
- **Deleting a template keeps its revisions**, so a campaign that pinned one still renders; the pointer row is the only
  thing removed.
- **`Segments` and the campaign snapshot share the audience api.** `sending` never reads `receivers` directly.

## Remaining

- `docker compose up` with the new `email-*` services, `ops/smoke/isolation.sh`, `terraform validate` and the GitHub
  workflows were not executed here (no Terraform binary; the full stack was not started). The compose file and the
  realm export were validated structurally.
- The Platform service-client registry/scope question from the plan is still an operator step: create the `email`
  replica client and set its hash, as notification required.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
