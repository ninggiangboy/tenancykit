---
phase: 12
title: "Tenant System migration"
status: pending
priority: P1
effort: "2d"
dependencies: [6, 9]
---

# Phase 12: Tenant System migration to suite tokens and Hub webhooks

## Goal

`tenant/` gets a short-lived JWT from Platform instead of sending a static API key, registers its webhook endpoint at the Hub, and verifies Standard Webhooks signatures. It still deploys without any suite code (`checkNoBillingDependency` stays green and gains Platform and Hub equivalents).

## Key insights

- `tenant/modules/billing-client/src/adapter/java/dev/ngb/tenant/billing/adapter/client/feign/BearerAuthInterceptor.java` sends `tenant.billing.api-key` on every call. It changes to ask a token provider for a cached token.
- `BillingWebhooks` (endpoint registration) belongs to the Hub now; it moves to a new `modules/notification-client` so the module name keeps telling the truth.
- Webhook verification lives in `tenant/modules/webhook/src/domain/java/dev/ngb/tenant/webhook/domain/event/WebhookSignature.java` and `adapter/event/web/WebhookController.java` (reads `Billing-*` headers). The route `/webhooks/billing` stays; headers and format change.
- `tenant.billing.tenant-system-id` is no longer configuration: Platform puts `tenant_system_id` in the token, but the tenant only needs it for display, so drop it.

## Files

Create:
- `tenant/modules/billing-client/src/adapter/java/dev/ngb/tenant/billing/adapter/client/token/PlatformTokenProvider.java`: client-credentials call to `POST {platform}/oauth/token` with `audience`, caches per audience until 60 seconds before expiry, single-flight refresh, clears the cache on a 401 from Billing and retries once.
- `tenant/modules/notification-client/` (layered module like `billing-client`): `api/client/NotificationWebhooks.java` (moved from `BillingWebhooks.java`), Feign adapter using the same token provider with `audience=notification`.

Modify:
- `BearerAuthInterceptor.java`, `BillingFeignClientFactory.java`, `HttpBillingSettings.java`: token provider instead of a static key.
- `tenant/apps/app-boot/src/main/resources/application.yml`: `tenant.platform.base-url`, `tenant.platform.client-id`, `tenant.platform.client-secret`, `tenant.notification.base-url`; remove `tenant.billing.api-key`, `tenant.billing.tenant-system-id`.
- `tenant/apps/app-boot/src/main/java/dev/ngb/tenant/boot/bootstrap/BootstrapRunner.java`: register the endpoint through `NotificationWebhooks`.
- `WebhookSignature.java`, `WebhookController.java`: Standard Webhooks headers; same tolerance and dedupe by `webhook-id`.
- `tenant/settings.gradle.kts`: include `modules:notification-client`.
- `tenant/build.gradle.kts`: extend `checkNoBillingDependency` to also reject `dev.ngb.platform` and `dev.ngb.notification` artifacts (and `core-messaging`, `core-replica`).
- `tenant/README.md`, `.env.example`: new variables.
- Tests: `WebhookSignatureTest`, `WebhookHttpIntegrationTest`, billing-client tests (WireMock token endpoint plus Billing).

Delete: `tenant/modules/billing-client/src/api/java/dev/ngb/tenant/billing/api/client/BillingWebhooks.java` after the move.

## Implementation steps

1. Token provider with tests (cache, refresh before expiry, concurrent callers share one refresh, 401 retry once).
2. Wire it into the Billing Feign client; configuration change.
3. `notification-client` module; move endpoint registration; bootstrap runner update.
4. Webhook verification to Standard Webhooks; tests with the spec's test vectors.
5. Dependency check extension.

## Todo

- [ ] `PlatformTokenProvider` with caching and refresh
- [ ] Billing client uses tokens; static key removed
- [ ] `notification-client` with endpoint registration
- [ ] Standard Webhooks verification
- [ ] Dependency check covers Platform, Hub and suite-only core modules

## Verification

- `cd tenant && ./gradlew build` green, including `checkNoBillingDependency`.
- `git grep -n "api-key\|SAMPLE_TENANT_BILLING_API_KEY" tenant` returns nothing.

## Risks

- Ships together with phase 9 (see its risk) so `dev` never has a Hub that the tenant cannot verify.

## Security

- The client secret comes from env; the token is never logged (Feign logging level stays `BASIC` or lower).
