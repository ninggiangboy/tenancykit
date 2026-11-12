---
phase: 9
title: "Notification Hub and webhook move"
status: pending
priority: P1
effort: "4d"
dependencies: [7, 8]
---

# Phase 9: Notification Hub skeleton and the webhook move

## Goal

Create the deployable `notification/` build and move Billing's whole `webhook` context into it. The Hub consumes `billing.events`, fans each public event out to the subscribed endpoints of that tenant, and signs and delivers with the existing lease, backoff, DEAD and resend rules. Billing's webhook code and tables are deleted.

## Key insights

- `billing/contexts/webhook` is self-contained: domain `delivery` and `endpoint`, use cases `RecordDeliveriesUseCase`, `DeliverWebhooksUseCase`, `ManageDeliveryUseCase`, `CreateEndpointUseCase`, `ManageEndpointUseCase`, the SSRF guard (`GuardedDnsResolver`, `AddressPolicy`), `HmacSha256Signer`, `RandomSigningKeys`. Only its input changes: `WebhookEventListener` (an in-process `@EventListener`) becomes an `InboundEventHandler` on the `notification.billing-events` queue.
- The AMQP body is the producer's final JSON (phase 2), so `JacksonEventBodyWriter` moves to the producer side (`core-messaging`'s `EnvelopeCodec`) and the Hub stores and signs the received bytes as they are.
- Endpoint ownership by tenant system (`billing/apps/app-boot/src/main/resources/db/migration/V10__webhook_endpoint_owner.sql`) keeps working through the Hub's `tenant_systems` replica.
- Header names are `Billing-*` today (`WebhookHeaders.java`). The Hub delivers for any app, so the names become neutral. This plan uses the **Standard Webhooks** names (`webhook-id`, `webhook-timestamp`, `webhook-signature` with `v1,<base64 HMAC-SHA256 of id.timestamp.body>`); this is a validation decision.
- Signing secrets stay envelope-encrypted with core `SecretCipher` (`AesGcmSecretCipher`); the Hub has its own master key.

## Files

Create the build like `platform/` in phase 4:
- `notification/settings.gradle.kts` (projects `libs:jooq-schema`, `contexts:webhook`, `apps:app-boot`, `testing:arch-fixtures`), build logic, wrapper, `.github/workflows/notification.yml`.
- `notification/contexts/webhook/`: moved from `billing/contexts/webhook/` with packages renamed `dev.ngb.billing.webhook` → `dev.ngb.notification.webhook`; `adapter/delivery/messaging/WebhookEventListener.java` replaced by `adapter/delivery/messaging/WebhookInboundHandler.java`.
- `notification/apps/app-boot/`: security on `core-replica` (`suite.app-code: notification`), jobs (webhook sender, housekeeping), `EventTypeCatalog` reading `contracts/events/billing/`.
- `notification/apps/app-boot/src/main/resources/db/migration/V1__baseline.sql`: role `notification_app`, RLS helpers, replica tables (`replica-tables.sql`), `event_inbox`, `audit_log`, `job_leases`, `job_runs`, and the webhook tables copied from Billing's `V7__webhooks.sql` and `V10__webhook_endpoint_owner.sql` plus any later webhook columns.
- Architecture tests and fixtures.

Modify:
- `notification/contexts/webhook/src/domain/java/dev/ngb/notification/webhook/domain/delivery/WebhookHeaders.java` and `HmacSha256Signer.java`: Standard Webhooks names and format.
- `contracts/events/README.md`: new headers and the verification recipe (Java and TypeScript).
- Billing: delete `billing/contexts/webhook/`, `boot/config/WebhookConfiguration.java`, `boot/jobs/WebhookJobs.java`, webhook settings in `application.yml`; remove `contexts:webhook` from `billing/settings.gradle.kts`; add `billing/apps/app-boot/src/main/resources/db/migration/V24__drop_webhooks.sql` dropping the webhook tables (keep `event_inbox`); regenerate jOOQ and OpenAPI.
- `contracts/openapi/notification.yaml` and `notification-tenant-system.yaml`: generated from the Hub like Billing's (`OpenApiContractTest` copied).

## Implementation steps

1. Build skeleton and CI; empty app green.
2. Move the webhook context with `git mv`, rename packages, port its tests.
3. Replace the in-process listener with the inbound handler; delivery body = message bytes.
4. Switch headers and signature to Standard Webhooks; update the signer tests with the published test vectors from the Standard Webhooks spec.
5. App wiring: replica, two-issuer security, entitlement filter for `notification`, permission codes `notification:webhook:read|manage`, jobs in the worker profile.
6. Integration test: a Billing-shaped `invoice.created` message on `billing.events` produces one signed POST to a registered endpoint (WireMock), retries on 500 with backoff, becomes `DEAD` after the limit, and can be resent.
7. Delete Billing's webhook context and tables; Billing build green.

## Todo

- [ ] `notification/` build and CI workflow
- [ ] Webhook context moved and fed from RabbitMQ
- [ ] Standard Webhooks headers and signature
- [ ] Hub security, replica, entitlement, permission codes
- [ ] Integration test for fan-out, retry, DEAD and resend
- [ ] Billing webhook code and tables deleted; contracts regenerated

## Verification

- `cd notification && ./gradlew build` green.
- `cd billing && ./gradlew build` green and `git grep -n "dev.ngb.billing.webhook" billing` returns nothing.

## Risks

- `tenant/` breaks between this phase and phase 12 (it registers endpoints at Billing and verifies `Billing-*` headers). Mitigation: phases 9 and 12 ship in the same merge to `dev`, or phase 12's webhook part is done on the same branch right after.
- SSRF guard blocks private addresses, which is what compose uses. Mitigation: keep the existing dev-only relaxation (the guard already allows plain HTTP in dev); add an explicit `notification.webhooks.allow-private-destinations` flag, false by default, true only in the compose profile.

## Security

- The SSRF guard moves unchanged and keeps checking inside the connection's own DNS resolution.
- Signing secrets are shown once, stored encrypted, rotated with overlap; the Hub's master key is a separate env var.
