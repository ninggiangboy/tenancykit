---
title: "Refactor review findings: security, transactions, core contracts and consistency"
description: "Fix every verified finding from the codebase review, then centralize the scattered strings, event types, names and constants and enforce the conventions in the build."
status: completed
priority: P1
effort: L
branch: dev
tags: [refactor, backend, security, tech-debt, contracts, messaging]
blockedBy: []
blocks: []
created: 2026-11-13
---

# Refactor review findings

## Outcome

Every finding from the two review passes is either fixed or explicitly recorded as a decision with evidence. The
result is four services that share one error taxonomy, one permission enforcement path, one set of architecture rules
and one contract vocabulary (event types, payload keys, headers, permission codes, actor/target names, config keys),
with the risky transaction and messaging paths corrected.

## Why (evidence)

The review found three classes of defect:

1. **Correctness and security.** A tenant administrator can create a `PLATFORM`-scope role and grant rights never
   issued (`RoleController:64`, `CreateRoleUseCase:34-41`, `Authorizer` checks only `has(code)`); credential
   rotate/revoke has no lock or version and can resurrect a revoked credential; `OpenPaymentMethodsUseCase:68` reads
   outside the transaction and always sees zero rows under RLS; tenant write flows use `UnitOfWork` in one place only;
   tenant idempotency keys are random; `EnvelopeCodec.decode` sets `aggregate = type` and drops/skips schema versions;
   `ReplicaWriter.strings` silently clears permissions.
2. **Structural drift.** Platform runs 19 ArchUnit rules, billing 37, tenant 28; three `PermissionInterceptor`
   implementations with different fail-open/fail-closed behaviour; three `ApiExceptionHandler` variants; four forked
   error taxonomies; six modules without an `api` source set.
3. **Scattered vocabulary.** Event types are declared three different ways and copied into five wiring points;
   `Idempotency-Key` (33), `X-Request-Id` (43) and `If-Match` (24) are literal in production; platform permission
   codes are raw strings; the AMQP envelope is snake_case while the webhook envelope is camelCase despite the
   contracts claiming they are the same document; payload keys and `money()` helpers are copied across five event
   classes; 22 aggregate prefixes and 24 idempotency-key prefixes are assembled inline.

## Decisions (confirmed with the user, 2026-11-13)

| # | Decision | Choice | Owning phase |
|---|---|---|---|
| D1 | Canonical envelope shape | One `EventDocument` writer: `id, type, tenantId, occurredAt, schemaVersion, data` for both AMQP and webhook; `schemaVersion` is bumped and the old shape is removed | 5, 8 |
| D2 | Error taxonomy names | Keep `AccessDeniedException`; add `BadRequestException` and `PaymentRequiredException` in `core-kernel`; delete the tenant fork | 1 |
| D3 | DDD base types | Keep `AggregateRoot`/`Entity`/`ValueObject` as they are; document which aggregates use them and why. No adoption or deletion | 10 |
| D4 | Permission interceptor | One `core-spring` interceptor, fail-closed; billing extends it for portal permissions; tenant switches to core annotations | 7 |
| D5 | Platform authz rule | A caller may only create/assign a role whose scope is not wider than the caller's; `tenantId` never comes from a body | 2 |
| D6 | Tenant idempotency | A write without a client key gets a deterministic key from stable business identity; a random key is never used | 4 |
| D7 | Event payload typing | Typed records per event, serialized by one mapper; `Map<String,Object>` stays only inside the codec | 8 |
| D8 | Shared architecture rules | New `core/core-arch` module holding the generic rules; each service keeps only its table/profile extras | 7 |
| D9 | Contract change policy | Breaking changes are allowed in one release for all in-repo producers and consumers; no dual-format compatibility window | 5, 8 |

## Non-goals

- The React frontend under `web/` (currently a placeholder) is not implemented by this plan; only the documentation
  claim is corrected.
- New product capabilities (new payment provider, new pricing model, new app in the suite).
- Changing the public REST paths or the database schema except where a finding requires it (credential `version`,
  broker claim deadline).
- Historical data migration beyond `api_credentials.version` defaulting to 1.

## Phases

| # | Phase | Depends on | Status |
|---|-------|-----------|--------|
| 1 | [Error taxonomy and HTTP mapping](./phase-01-error-taxonomy-and-http.md) | none | Pending |
| 2 | [Platform security and correctness](./phase-02-platform-security-and-correctness.md) | 1 | Pending |
| 3 | [Billing transaction and idempotency](./phase-03-billing-transaction-and-idempotency.md) | 1 | Pending |
| 4 | [Tenant transaction and integration](./phase-04-tenant-transaction-and-integration.md) | 1 | Pending |
| 5 | [Core messaging contract](./phase-05-core-messaging-contract.md) | 1 | Pending |
| 6 | [Core replica, sync, suite and feign](./phase-06-core-replica-sync-suite-feign.md) | 1, 5 | Pending |
| 7 | [Permission interceptor and shared ArchUnit rules](./phase-07-permission-interceptor-and-archunit.md) | 1, 2, 4 | Pending |
| 8 | [Contract constants and naming](./phase-08-contract-constants-and-naming.md) | 3, 4, 5, 7 | Pending |
| 9 | [Domain status, type and configuration constants](./phase-09-domain-status-config-constants.md) | 4, 8 | Pending |
| 10 | [Clean code and DDD sweep](./phase-10-clean-code-and-ddd-sweep.md) | 2–9 | Pending |
| 11 | [Test and documentation hardening](./phase-11-test-and-docs-hardening.md) | all | Pending |

Phase 1 is the foundation and lands first. Phases 2, 3, 4 and 5 touch disjoint services/core modules and may run in
parallel after phase 1. Phase 7 depends on the security rules from phase 2 and the tenant conversion in phase 4.
Phases 8 and 9 centralize vocabulary after the behaviour is correct. Phase 10 cleans what remains; phase 11 closes
the evidence gaps and the documentation drift.

## Cross-cutting verification

- `cd core && ./gradlew build`
- `cd platform && ./gradlew build`
- `cd billing && ./gradlew build`
- `cd tenant && ./gradlew checkNoBillingDependency build`
- `docker compose up -d --build --wait && ops/smoke/isolation.sh && ops/smoke/suite-smoke.sh`
- Grep gates (must return no production hit): `dev.ngb.tenant.shared.error`, `@RequiresPermission("`,
  `Map.of("minorUnits"`, `"Idempotency-Key"` outside `HttpHeaders`, `new NamedRule(` outside `core-arch` +
  service-specific extras.

## Global risks

- The envelope/event-name changes (phases 5 and 8) touch a cross-service contract and are allowed to break in one
  release. Mitigation: land producer and every in-repo consumer in the same change, bump `schemaVersion`, update the
  contracts and the suite smoke test, and keep a single deploy window for all services.
- The authz change (phase 2) can reject legitimate platform administrators. Mitigation: seed/testing matrix covering
  platform admin, tenant admin, tenant system and service client, plus a rollout note.
- Moving architecture rules (phase 7) can surface latent violations. Mitigation: phase 2–6 fixes the known violations
  first; the shared rule set lands with a fixture proving every rule fails.
- Docker-backed tests may not run in every environment; each phase states the compile-only fallback and reports it
  instead of claiming a pass.

## Unresolved questions

None. D1, D3 and D9 were answered on 2026-11-13. Any new ambiguity found during a phase is recorded in that phase's
Risk Assessment and resolved there before the phase lands.
