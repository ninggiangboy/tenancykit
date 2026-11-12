---
phase: 10
title: "Email and templates"
status: pending
priority: P1
effort: "3d"
dependencies: [9]
---

# Phase 10: Email with per-tenant templates

## Goal

Billing sends a `notification.requested` command when an invoice is created; the Hub renders the tenant's template (or the default) in the requested locale and delivers the email over SMTP with the same lease, retry, DEAD and resend rules as webhooks. In compose the email lands in Mailpit.

## Key insights

- **Second real delivery channel.** Webhook delivery and email delivery share claim-with-lease, attempts, exponential backoff, `DEAD` and resend. With two real channels the shared part is extracted now into `notification/libs:delivery`; channel-specific parts (HTTP send and signing vs SMTP send and rendering) stay in their contexts.
- **The owning app decides who is notified.** Billing resolves recipients: the customer's email (`customers.email`, nullable) for email, and active users holding `billing:invoice:read` in that tenant (from Billing's `user_grants` replica) for in-app (phase 11). The Hub never interprets Billing's business events for email or in-app.
- `invoice.created` is raised by several use cases (`InvoiceEvents.java`), so Billing reacts once, in an in-process outbox consumer, not in each use case.
- Templates: logic-less JMustache with HTML escaping on, strict mode (a missing variable fails the render and the request is recorded as failed, not sent half-filled). Defaults ship as classpath files; a tenant override is a database row.
- Email provider configuration is suite-wide (one SMTP server). Per-tenant sender name and reply-to are tenant settings in the Hub.

## Files

Create:
- `notification/libs/delivery/`: `DeliveryPolicy`, `DeliveryStatus`, `DeliveryAttempt`, lease and claim helpers extracted from `notification/contexts/webhook/src/domain/.../delivery/` and the claim SQL of `JooqWebhookDeliveryRepository`; webhook refactored to use it in the same commit, its tests unchanged.
- `notification/contexts/email/` (package root `dev.ngb.notification.email`): domain `EmailRequest`, `EmailDelivery`, `Template`, `TemplateKey`, `Locale`; application `AcceptNotificationRequestUseCase` (validates against `contracts/commands/notification.requested.schema.json`, records one email delivery per email recipient), `DeliverEmailsUseCase`, `ManageTemplateUseCase`, `ManageSenderSettingsUseCase`, queries; adapter `messaging/NotificationRequestHandler` (queue `notification.requests`), `render/MustacheRenderer`, `smtp/SpringMailSender` (`JavaMailSender`), persistence, web (`TemplateController`, `EmailDeliveryController`).
- `notification/contexts/email/src/adapter/resources/templates/billing.invoice-created/{en,vi}.{subject,html,txt}.mustache`.
- `notification/apps/app-boot/src/main/resources/db/migration/V2__email.sql`: `notification_requests` (request id unique per tenant for idempotency), `email_templates` (tenant_id, key, locale, subject, html, text, version), `sender_settings`, `email_deliveries`, `email_delivery_attempts`, with RLS.
- `billing/contexts/invoicing/src/application/java/dev/ngb/billing/invoicing/application/invoice/command/NotifyInvoiceCreatedUseCase.java`: on `invoice.created` (inbox consumer `invoicing.notify`), builds `notification.requested` with `template_key = billing.invoice-created`, variables (invoice number, total, due date, portal link), recipients, and publishes it through `EventPublisher`.
- A Billing read port for "active users with permission X in this tenant" over `user_grants` (in `billing/contexts/invoicing`, adapter with jOOQ).

Modify:
- `billing/contexts/invoicing/src/adapter/java/dev/ngb/billing/invoicing/adapter/invoice/messaging/InvoicingEventListener.java`: call the new use case.
- Billing `application.yml`: export route `notification.requested` → `notification.requests`.
- Hub `application.yml`: `spring.mail.*` from env; queue `notification.requests`.
- `docker-compose.yml`: `mailpit` (`axllent/mailpit`, SMTP 1025, UI 8025).
- `contracts/permissions/notification.yaml` already holds the template codes (phase 2).

## Implementation steps

1. Extract `libs:delivery` and refactor webhook onto it; webhook tests green unchanged.
2. Email domain and request acceptance with idempotency on `(tenant_id, request_id)`.
3. Renderer with strict mode and HTML escaping; tests for escaping (`<script>` in a variable is escaped), missing variable (fails), locale fallback (`vi` missing → `en`), tenant override wins over default.
4. SMTP sender and delivery job in the worker profile; Testcontainers Mailpit (GenericContainer) test: one request → one message with the right subject and recipient; SMTP down → retry then `DEAD`; resend works.
5. Template and sender-settings APIs with `notification:template:read|manage`.
6. Billing `NotifyInvoiceCreatedUseCase` and its tests (customer without email → no email recipient; users resolved from `user_grants`).
7. End-to-end inside the Hub test: a `notification.requested` message on the queue produces one Mailpit message.

## Todo

- [ ] `libs:delivery` extracted, webhook refactored
- [ ] Email request acceptance with idempotency
- [ ] Strict, escaping Mustache renderer with locale fallback and tenant overrides
- [ ] SMTP delivery with retry, DEAD and resend
- [ ] Template and sender APIs
- [ ] Billing sends `notification.requested` on `invoice.created`
- [ ] Mailpit in compose; integration tests

## Verification

- `cd notification && ./gradlew build` and `cd billing && ./gradlew build` green.
- With compose: create an invoice for a customer with an email; `curl -s http://localhost:8025/api/v1/messages | jq '.total'` increases by 1.

## Risks

- Template injection. Mitigation: logic-less templates, escaping on, tenants cannot add partials or lambdas.
- Personal data in `notification_requests.variables`. Mitigation: housekeeping deletes request payloads after 30 days (keep delivery status rows).

## Security

- SMTP credentials from env.
- Template APIs are tenant-scoped by RLS; a tenant cannot read or edit another tenant's templates or the defaults.
