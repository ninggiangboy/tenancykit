---
title: CRM and loyalty apps advised and planned
date: 2026-11-16
summary: /ak-advise turned "add a crm service" into two API-only apps (crm customer data hub, loyalty points program) and a 16-phase plan; scouting found Platform's service-client list lacks mailroom's entry
---

# CRM and loyalty apps advised and planned

## What happened
- /ak-advise started from "crm" and narrowed it step by step. The user wanted a customer hub that does not make other services depend on it, then clarified it is for customers who already bought at a retail Tenant (supermarket-style), not a sales CRM. They dropped case handling and analytics, and added loyalty as a separate app delivered in the same phase.
- The Tenant's own POS owns identity (`external_ref`) and pushes orders with line items to crm by API or batch. crm publishes `crm.events`; loyalty listens. crm does not listen to Billing, mailroom, survey or attribution; those apps stay untouched.
- Wrote plans/261116-1023-crm-loyalty-apps/ (16 phases: 9 crm-side and shared, 7 loyalty-side) with a path verification pass, an inline red-team and four policy questions, all answered with the recommended default.

## Verified findings that changed the design
- Billing's `customer.created` carries no email and maps customers to Tenant System users, so crm cannot be a hub fed by existing events; the Tenant's POS has to push purchases.
- Platform's `application.yml` lists service clients `billing` and `notification` only, while compose passes `PLATFORM_MAILROOM_SERVICE_CLIENT_*`; nothing reads them. Phase 2 tests whether mailroom's replica client authenticates (unverified; possible pre-existing defect also affecting survey and attribution).
- Platform builds its list of apps from `contracts/permissions`, so no Platform Java changes, but Platform must be rebuilt before `crm` or `loyalty` can be enabled.
- The red-team removed three designs: loyalty consuming events of every Tenant (would fill tables for crm-only Tenants), an erasure retry record that kept the POS id, and a stored balance that disagreed with the computed one.

## Decision
- Order events carry the order's full state and an `orderVersion`, so loyalty needs no delivery ordering; a return recomputes with the rules snapshot stored at the first earn and writes the delta. Points live in lots; a return after spending becomes debt that blocks redemption until repaid. Balance is computed from unexpired lots on read. Tiers: rolling spend, rise at once, fall in the scheduled evaluation. No backfill of orders from before loyalty was enabled (open question).

## Next steps
- Run phase 1 (contracts and earn vectors), then phase 2 (check mailroom's service client first), then phases 3 and 4 in parallel.
- Survey and attribution edit the same ops files: phases 2 and 16 are append-only and re-run the `git grep` first.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
