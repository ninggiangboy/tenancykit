---
title: Email app — phases 1-3 delivered
date: 2026-11-15
summary: "Docs/contracts, mailer result events+headers+tenant registration, and the email service skeleton with replica, gates and RLS"
---

# Email app — phases 1-3 delivered

## Outcome

Implemented phases 1-3 of `plans/261115-1849-email-app`.

### Phase 1 — docs and contracts
- Reversed the two non-goals in `docs/overview-and-scope.md` with the dated reason; added the fifth deployable.
- Added `docs/email.md`, a permission catalog (`contracts/permissions/email.yaml`), the
  `email-delivery.changed` event schema, an additive `headers` allowlist on `email.send`, and the
  `contracts/openapi/email.yaml` resource skeleton.
- Updated notification/mailer/suite-messaging/architecture docs and the topology tables.

### Phase 2 — mailer
- Terminal delivery outcomes now publish `email-delivery.changed` to `mailer.events` through a
  transactional outbox, in the same transaction as `recordOutcome` and guarded by the CAS.
- Accepting an `email.send` registers its tenant in `mailer_tenants`, fixing the gap where an ordinary
  tenant's command sat `PENDING` for ever.
- Allowlisted `headers` reach SES v2 and SMTP, stored on `email_bodies.headers`.
- Broker permissions and the isolation smoke updated: mailer may publish only `mailer.events`.

### Phase 3 — email service skeleton
- New `email/` Gradle build (`dev.ngb.email`) with `platform`, `content`, `audience`, `sending` contexts,
  three runtimes (`migrate`, `api`, `worker`), the Platform replica, the `email.platform-events` and
  `email.mailer-events` queues, the outbox, `email_settings`, and an `email.send` export.
- Architecture gates (ArchUnit + Modulith + fixture meta-test), jOOQ drift test, per-table RLS test,
  replica apply test, runtime startup tests, guard/catalog/OpenAPI contract tests.

## Evidence
- `cd mailer && ./gradlew build` — green; affected integration tests cover SUCCEEDED/DEAD events,
  tenant registration, headers on both transports, RLS, jOOQ drift.
- `cd notification && ./gradlew :apps:app-boot:test --tests '*EmailCommandContractTest'` — green.
- `cd email && ./gradlew build` — green, 89 tests.

## Remaining
Phases 4-10: templates and the sandboxed renderer; receivers/groups/suppression/unsubscribe; single and
batch email plus results; campaigns; tracking/public endpoints/stats; segments/housekeeping; ops,
end-to-end and final docs.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
