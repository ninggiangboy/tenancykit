---
phase: 11
title: "Public namespace: Billing"
status: pending
priority: P1
effort: 2d
dependencies: [10]
---

# Phase 11: Public namespace: Billing

## Goal
Billing's customer portal and its inbound Stripe webhooks move under `/public/billing/...`, with the portal
operations labelled `customer-session` and the webhooks `provider`.

## Route changes
| Today | After | Caller |
|---|---|---|
| `/portal/api/**` (wallets, payments, invoices, top-ups, usage, subscriptions, refunds, customer, charges) | `/public/billing/portal/api/**` | customer-session |
| `POST /portal/api/session/exchange` | `/public/billing/portal/api/session/exchange` | public (single-use token) |
| `/stripe/webhooks/direct/{configurationId}`, `/stripe/webhooks/connect` | `/public/billing/stripe/webhooks/...` | provider |

## Files to Create / Modify
- Modify: Billing's portal and Stripe webhook controllers, the security configuration, the portal cookie path
  (`BILLING_PORTAL_SECURE_COOKIE` family), the session URL builder (`BILLING_PUBLIC_BASE_URL`), the webhook URL a
  Tenant configures in Stripe and the one Billing displays
- Modify: `ops/caddy/Caddyfile` (`/public/billing/*` handle without strip, before the catch-all that sends everything
  else to Billing), `ops/terraform/billing/` ALB rule
- Modify: `contracts/openapi/billing.yaml` and per-caller files (regenerated); `docs/billing-session-and-portal.md`,
  `docs/payments-and-stripe.md`, `ops/seed/billing.sh`, smoke and e2e support
- Modify: the Stripe CLI or test instructions that forward webhooks to a local path

## Tasks & Steps
1. Move the controllers; check cookie `Path`, CORS (if any) and the portal return URL logic against the new prefix.
2. Update the webhook URLs Billing emits and documents; update the Stripe test helpers.
3. Update Caddy and the ALB; the catch-all keeps serving `/api/v1` and `/system/v1`.
4. Regenerate the contract and confirm the labels: `customer-session` for the portal, `provider` for Stripe.
5. Extend `CallerLabelRules` so that every `customer-session`, `public` and `provider` path must start with
   `/public/<app>/`; all ten documents now satisfy it (the Platform exceptions `/oauth/token` and jwks are listed).

## Verification
- `./gradlew test` in Billing (webhook signature tests, portal session tests).
- `make smoke`: exchange a billing session, read a wallet through the portal, replay a signed Stripe test event.
- Old `/portal/**` and `/stripe/webhooks/**` answer 404 and appear nowhere in code, docs or ops.

## Risks
- Billing serves portal HTML somewhere (phase 1 answers this); if so, that path moves too.
