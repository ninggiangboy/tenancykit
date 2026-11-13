---
title: Refactor review findings plan
date: 2026-11-13
summary: "Planned the remediation of the codebase review: security, transactions, core messaging, architecture consistency, contract vocabulary and tests/docs across 11 phases."
---

# Refactor review findings plan

## What happened
Two review passes over core, platform, billing and tenant produced verified findings in six areas: authorization/security, transaction boundaries and idempotency, shared-core messaging/replica behaviour, architecture-rule drift, scattered contract vocabulary (event types, headers, permission codes, payload keys, actor/target names, config keys), and missing tests/docs drift.

The plan was scaffolded with `ak plan create` and 11 phases were written under `plans/261113-1413-refactor-review-findings/`. Each phase carries evidence (file:line), files to change, tasks, verification commands and success criteria. Phases hydrate into task #15–#25 with dependencies. `ak plan validate` reports the plan valid: 11 phases, 58 tasks.

## Key plan shape
1. Error taxonomy + shared RFC 9457 handlers.
2. Platform security (scope-aware authz, credential version/locking, Keycloak outside tx, token rate limit, access audit).
3. Billing transaction/idempotency (RLS read, typed event decoders, atomic Payment.succeed).
4. Tenant transaction/integration (UnitOfWork, order-first Billing calls, deterministic keys, verify-before-parse).
5. Core messaging (envelope aggregate/schema_version, broker claim/publish split, shared relay rows).
6. Core replica/sync/suite/feign hardening.
7. One permission interceptor + shared `core-arch` rules with fixtures/parity test.
8. Contract constants (EventTypes, HttpHeaders, MoneyPayload, PlatformPermissions, actor/target/key names, metrics).
9. Domain enums + typed configuration records.
10. Clean-code/DDD sweep and DDD base-type decision.
11. Tests and documentation hardening.

## Decisions to confirm
- Envelope shape (camelCase canonical vs keep AMQP snake_case + docs).
- Adopt or delete the unused `AggregateRoot`/`Entity`/`ValueObject` base types.
- Whether phases 5/8 may make a breaking contract change or must stay backward-compatible for one release.

## Next steps
Answer the three open questions, then run `/ak-cook plans/261113-1413-refactor-review-findings/plan.md`.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
