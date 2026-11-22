---
phase: 13
title: "Public containers for the remaining apps"
status: pending
priority: P1
effort: 3d
dependencies: [12]
---

# Phase 13: Public containers for the remaining apps

## Goal
Mailroom, Attribution, Booking and Billing run their public surface as `*-public` containers, giving six in total.

## Files to Create / Modify
- Modify (per app, one commit per app): `@PublicSurface` on the public controllers, `application-public.yml`, security
  configuration, ArchUnit tests
- Modify: `docker-compose.yml` (`mailroom-public`, `attribution-public`, `booking-public`, `billing-public`; reuse the
  existing `x-*-environment` anchors so the settings are not duplicated)
- Modify: `ops/caddy/Caddyfile` (public upstream variables for the four apps; the Billing catch-all now serves only
  `/api/v1` and `/system/v1`)
- Modify: `ops/terraform/{mailroom,attribution,booking,billing}/services.tf`, `task-defs.tf`, `alb.tf`
- Modify: `.github/workflows/*.yml` only where a smoke step names the old services
- Modify: `docs/runtime-profiles.md`, `ops/terraform/README.md` (table of workloads)

## Needs per app
| App | Public runtime needs |
|---|---|
| Mailroom | DB, replica reads, tracking secret, rate cap; writes tracking events |
| Attribution | DB, replica reads, click-token secret, broker publisher for `attribution.ingest` |
| Booking | DB, replica reads, customer-token secret, admission limits |
| Billing | DB, replica reads, Stripe configuration and `BILLING_SECRETS_MASTER_KEY`, portal session store, outbox write |

## Tasks & Steps
1. For each app: mark controllers, add the profile, run the architecture and profile tests.
2. Compose first (it exercises the real wiring), then Terraform and the ALB.
3. Run the negative route checks for every app: authenticated path to `*-public` 404, `/public/...` to `*-api` 404.
4. Run smoke and e2e on the full stack.

## Verification
- `./gradlew test` in each of the four services.
- `docker compose ps` shows six `*-public` containers healthy; `make smoke` and `pnpm -C frontend e2e` pass.
- `terraform validate` passes; the ALB path rules reference only `/public/<app>/*` for public targets.

## Risks
- Billing's public runtime touches the most beans (portal plus Stripe); do Billing last and keep it a separate commit.
- If at this point the operational cost looks too high, stop: the namespace and labels are complete without it, and
  merging a public profile back into `api` means deleting the profile and its services.
