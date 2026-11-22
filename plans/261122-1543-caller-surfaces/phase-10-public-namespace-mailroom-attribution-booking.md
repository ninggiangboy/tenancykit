---
phase: 10
title: "Public namespace: Mailroom, Attribution, Booking"
status: pending
priority: P1
effort: 3d
dependencies: [9]
---

# Phase 10: Public namespace: Mailroom, Attribution, Booking

## Goal
Recipient tracking, tracked links and the booking gate move under `/public/<app>/...`, one app per commit, using the
pattern from phase 9.

## Route changes
| App | Today | After | Caller |
|---|---|---|---|
| Mailroom | `/mailroom/t/{u,o,c}/{token}` | `/public/mailroom/t/{u,o,c}/{token}` | public |
| Attribution | `/go/{slug}` | `/public/attribution/go/{slug}` | public |
| Attribution | `/t/v1/events` (browser collection) | `/public/attribution/t/v1/events` | public |
| Attribution | conversion postbacks and ad-platform callbacks, if any (phase 1 inventory) | `/public/attribution/...` | provider |
| Booking | `/booking/p/**` and the root `/p/**` alias | `/public/booking/p/**` (alias removed) | public |

## Files to Create / Modify
- Modify: `backend/services/apps/mailroom/contexts/sending/src/adapter/java/dev/ngb/mailroom/sending/adapter/tracking/web/PublicTrackingController.java`
  and the tracking URL builder driven by `MAILROOM_PUBLIC_BASE_URL`
- Modify: the Attribution redirect and collection controllers, the tracked-link builder, the site snippet text and
  `ATTRIBUTION` public base URL settings
- Modify: `backend/services/apps/booking/apps/app-boot/src/main/java/dev/ngb/booking/boot/gate/PublicController.java`,
  customer-token docs, `contracts/openapi/booking.yaml` (hand-written)
- Modify: `ops/caddy/Caddyfile` (delete `@attributionPublic /go/* /t/*`; add `/public/<app>/*` handles without strip),
  `ops/terraform/{mailroom,attribution,booking}/alb.tf` and the variable descriptions that mention `/go/` and `/t/`
- Modify: `docker-compose.yml` public base URL defaults; `ops/seed/{attribution,mailroom,booking}.sh`,
  `ops/loadtest/booking.sh`, smoke scripts
- Modify: `contracts/openapi/{mailroom,attribution,booking}.yaml` and per-caller files; `docs/{mailroom,attribution,booking}.md`

## Tasks & Steps
1. Mailroom: move the controller and the URL builder; confirm email templates build tracking links from the base URL.
2. Attribution: move redirect and collection; the old short `/go/` is gone by decision (assumption in `plan.md`);
   update every example that shows a tracked link.
3. Booking: move the gate; remove the root alias; update the customer-token flow docs.
4. Edge and compose and seeds per app; contracts regenerated or edited; old paths answer 404.

## Verification
- `./gradlew test` in each of the three services; `make smoke`; load test script for Booking still runs.
- A search for `/go/`, `/mailroom/t/`, `/booking/p/` and root `/t/` outside `/public/` returns nothing.

## Risks
- Tracking links already embedded in rendered templates stored in a database: no environment is live, so reseed.
