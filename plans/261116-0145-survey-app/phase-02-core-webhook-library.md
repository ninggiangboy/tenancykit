---
phase: 2
title: "Shared webhook library and Billing migration"
status: done
priority: P1
effort: "M"
dependencies: [1]
---

# Phase 2: Shared Webhook Library and Billing Migration

## Goal

Move Billing's webhook mechanics (signing, destination policy, guarded DNS, HTTP send, retry schedule) into a new
`core/core-webhook` module and switch Billing onto it with no change to its wire format, tables or API.

## Context

Verified 2026-11-16: the classes below depend only on `core-kernel` (`dev.ngb.core.util.Backoff`,
`dev.ngb.core.http.HttpHeaders`) and on Billing's own ports. Endpoint and delivery tables, repositories, controllers,
use cases and the event-type catalog are Billing's and stay.

| Billing class | Lines | Becomes |
|---|---|---|
| `adapter/delivery/crypto/HmacSha256Signer` | 28 | `dev.ngb.core.webhook.WebhookSigner` |
| `domain/delivery/WebhookHeaders` | 23 | `dev.ngb.core.webhook.WebhookHeaderSet` (record of names; `BILLING` and `SURVEY` constants) |
| `domain/endpoint/AddressPolicy` | 81 | `dev.ngb.core.webhook.DestinationPolicy` |
| `adapter/delivery/http/GuardedDnsResolver` | 39 | `dev.ngb.core.webhook.GuardedDnsResolver` |
| `adapter/delivery/http/HttpComponentsWebhookSender` | 130 | `dev.ngb.core.webhook.WebhookHttpSender` |
| `adapter/delivery/http/BlockedDestinationException` | 13 | `dev.ngb.core.webhook.BlockedDestinationException` |
| `domain/delivery/DeliveryPolicy` | 29 | `dev.ngb.core.webhook.RetrySchedule` |
| `domain/endpoint/WebhookUrl` | 70 | `dev.ngb.core.webhook.WebhookUrl` |

## Files to create / modify

- Create: `core/core-webhook/build.gradle.kts` (`java-library`, `java-conventions`; `api(project(":core-kernel"))`,
  `implementation("org.apache.httpcomponents.client5:httpclient5")`), the classes above, and their tests
- Modify: `core/settings.gradle.kts` (include `core-webhook`); `core/core-arch/src/main/java/dev/ngb/core/arch/ArchitectureRules.java`
  and `ArchitectureRulesTest` gain a `core-webhook-is-self-contained` rule like `core-messaging-is-self-contained`
  (JDK, `core-kernel`, HttpClient 5 and SLF4J only)
- Modify: `core/core-kernel/src/main/java/dev/ngb/core/http/HttpHeaders.java` (add `SURVEY_*` constants)
- Modify: `billing/contexts/webhook/build.gradle.kts` (add `dev.ngb:core-webhook`)
- Modify: Billing's `PayloadSigner`, `WebhookSender` ports stay; their adapters delegate to the core classes; delete the
  moved classes from Billing and update imports
- Move tests: Billing's unit tests for the moved classes move to `core/core-webhook/src/test`; Billing's integration
  tests stay

## Tasks

1. Before moving anything, add a **golden test** in Billing: a fixed secret, timestamp and body produce a fixed
   `Billing-Signature`, and a fixed attempt produces fixed headers. Commit it first so the refactor is proven against it.
2. Create `core-webhook` and move the classes, keeping behaviour identical. `WebhookHeaderSet` is the only new type:
   Billing uses `WebhookHeaderSet.BILLING`, which reads the existing `HttpHeaders.BILLING_*` constants.
3. Billing's adapters become thin delegates; Billing's ports and use cases are unchanged.
4. Keep `DestinationPolicy` configuration (allowed schemes, blocked ranges, the local-development allowance) exactly as
   Billing configures it today; the core class takes it as constructor input.
5. Update `docs/webhooks-and-events.md` with one paragraph naming `core-webhook` as the shared mechanics and stating that
   tables and delivery state stay per app.

## Verification

- `cd core && ./gradlew build` green, including the moved unit tests.
- `cd billing && ./gradlew build` green, including the golden signature test written in task 1.
- `cd tenant && ./gradlew test` green (it verifies Billing's signatures).
- `git grep -n "class HmacSha256Signer\|class GuardedDnsResolver" billing/` returns nothing.

## Risks

- A silently changed header name or signature byte breaks every Tenant System. The golden test is the guard and must
  exist before the move.
- Billing's virtual-thread or timeout settings may be wired in Spring config; keep that wiring in Billing and pass the
  values into the core sender.
