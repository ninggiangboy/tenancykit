---
phase: 12
title: "Tenant System migration"
status: pending
priority: P1
effort: "1d"
dependencies: [6]
---

# Phase 12: Tenant System migration to suite tokens

## Goal

`tenant/` gets a short-lived JWT from Platform instead of sending a static API key. Webhook endpoint registration and signature verification stay exactly as they are, because webhooks stay in Billing. It still deploys without any suite code (`checkNoBillingDependency` stays green and gains Platform equivalents).

## Key insights

- `tenant/modules/billing-client/src/adapter/java/dev/ngb/tenant/billing/adapter/client/feign/BearerAuthInterceptor.java` sends `tenant.billing.api-key` on every call. It changes to ask a token provider for a cached token.
- `BillingWebhooks` (endpoint registration) and the webhook verification in `tenant/modules/webhook` (`WebhookSignature`, `WebhookController`, the `Billing-*` headers) do not change. The same token provider authenticates the registration call.
- `tenant.billing.tenant-system-id` is no longer configuration: Platform puts `tenant_system_id` in the token, but the tenant only needs it for display, so drop it.
- This phase depends only on phase 6 (Platform issues the tokens). It no longer has to ship together with another phase.

## Files

Create:
- `tenant/modules/billing-client/src/adapter/java/dev/ngb/tenant/billing/adapter/client/token/PlatformTokenProvider.java`: client-credentials call to `POST {platform}/oauth/token` with `audience=billing`, caches until 60 seconds before expiry, single-flight refresh, clears the cache on a 401 from Billing and retries once.

Modify:
- `BearerAuthInterceptor.java`, `BillingFeignClientFactory.java`, `HttpBillingSettings.java`: token provider instead of a static key.
- `tenant/apps/app-boot/src/main/resources/application.yml`: `tenant.platform.base-url`, `tenant.platform.client-id`, `tenant.platform.client-secret`; remove `tenant.billing.api-key`, `tenant.billing.tenant-system-id`.
- `tenant/build.gradle.kts`: extend `checkNoBillingDependency` to also reject `dev.ngb.platform` artifacts (and `core-messaging`, `core-replica`).
- `tenant/README.md`, `.env.example`: new variables.
- Tests: billing-client tests (WireMock token endpoint plus Billing).

## Implementation steps

1. Token provider with tests (cache, refresh before expiry, concurrent callers share one refresh, 401 retry once).
2. Wire it into the Billing Feign client; configuration change.
3. Dependency check extension.

## Todo

- [ ] `PlatformTokenProvider` with caching and refresh
- [ ] Billing client uses tokens; static key removed
- [ ] Dependency check covers Platform and suite-only core modules

## Verification

- `cd tenant && ./gradlew build` green, including `checkNoBillingDependency`.
- `git grep -n "api-key\|SAMPLE_TENANT_BILLING_API_KEY" tenant` returns nothing.

## Risks

- Phase 8 removed Billing's bearer-secret path, while `tenant/` still sends `tenant.billing.api-key`, so tenant-to-Billing calls are not expected to authenticate on `dev` until this phase lands. Do this phase soon; it has no dependency left on any other pending phase.

## Security

- The client secret comes from env; the token is never logged (Feign logging level stays `BASIC` or lower).
