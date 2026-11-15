---
title: Implemented the notification service
date: 2026-11-15
summary: "Built notification/ as the suite's fourth deployable through phases 1-7 plus the ops seams; notification and platform builds green, integration/e2e tests remain."
---

# Implemented the notification service

## What happened

Cooked `plans/261115-0619-notification-service` with `--auto --parallel`. The new `notification/` Gradle build now exists with two contexts (`platform` for the replica, `dispatch` for catalog/templates/in-app/email), three runtimes (`migrate`, `api`, `worker`), ArchUnit + meta + Modulith gates, a jOOQ drift test, and an isolation gate.

Implemented and verified:

- Phase 1: scope reversal in `docs/overview-and-scope.md`, architecture and both messaging topology tables, `contracts/permissions/notification.yaml`. The catalog file is packaged into Platform's jar.
- Phase 2: build skeleton, `V1__baseline.sql` (replica tables, inbox without a tenant FK, `replica_state`, platform pseudo-tenant), committed jOOQ schema, four architecture tests, three profiles, Dockerfile.
- Phase 3: `PlatformEventsHandler` reads the previous snapshot, upserts with the version guard, then announces the transition; `TenantSweep`; readiness contributor bean-named `replicaState`; Feign snapshot transport; the second Platform service client entry.
- Phase 4: 50 classpath templates (5 types × en/vi × 5 parts), strict JMustache model, HTML/text escape modes, forbidden construct/size policy, locale fallback.
- Phase 5: five transition rules, catalog truth table, audience resolver, settings/preferences, in-app store with the second dedupe key.
- Phase 6: dispatch ledger, contract-validated `email.send` factory with `fromName` sanitisation and omission, outbox wiring with a non-empty export assertion, relay and housekeeping jobs.
- Phase 7: 12 endpoints, `TenantScopeAndUserGuard`, security chain, query classes, generated `contracts/openapi/notification.yaml`, permission catalog test.
- Phase 8: compose services, DB roles in both init scripts, scoped broker user plus positive `mailer.commands` control, Caddy `/notification/*`, Keycloak audience mapper, Terraform module and root wiring, CI workflow and deploy entries, docs `notification.md`, smoke script.

## Evidence

- `cd notification && ./gradlew build` passes (architecture, meta, Modulith, jOOQ drift, unit + replica tests).
- `cd platform && ./gradlew build` passes with the second `service-clients` entry.
- `git diff --stat mailer/` and `git diff --stat billing/` are empty; `platform/` differs only by `application.yml`.
- Boot jars contain `commands/email.send.schema.json` and `permissions/notification.yaml`.

## Deliberate deviations from the plan

- `contexts/platform` collides with the sibling service name in the isolation gate's project-path axis, so that axis now matches the first path segment only; the external-coordinate axis is unchanged.
- Platform API types live in `platform.api.replica` (a capability package), because `role-package-not-layer-root` forbids classes directly in a layer root; the `@NamedInterface` package-info is in the adapter source set as Billing does.
- The JMustache renderer is an adapter class behind the `TemplateRenderer` port: the application layer's classpath has no template engine and `inner-layer-purity` forbids one.
- `notification_templates` and the renderer share `TemplatePolicy`, so the write API and the read path refuse the same input.
- Dispatch rows are written `PUBLISHED` when the outbox row commits; the column comment labels it "queued for mailer", not delivery truth.

## Open gaps

- Not written: `ReplicaBootstrapTest`, `ReplicaReadinessTest`, `AcceptChangeUseCaseTest`, `NotificationDedupeIntegrationTest`, `EmailSkipRulesTest`, `OutboxRelayIntegrationTest`, `HousekeepingIntegrationTest`, `NotificationApiTest`, `SuiteTokenSecurityTest`.
- `docker compose up`, `ops/smoke/isolation.sh`, `ops/smoke/notification-smoke.sh`, and `terraform validate` were not run here; Terraform is not installed locally.
- Phase 3 build/readiness tests and the e2e criteria remain the evidence that would move the plan from `in-progress` to `completed`.

## Next steps

Write the Spring/Testcontainers integration tests above, run the compose stack plus both smoke scripts, and run `terraform fmt/validate` in CI.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
