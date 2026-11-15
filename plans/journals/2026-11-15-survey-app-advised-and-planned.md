---
title: Survey app advised and planned
date: 2026-11-15
summary: Scoped survey/ via /ak-advise and wrote a 10-phase plan; dropped REST-to-mailroom after verifying service tokens carry no tenant
---

# Survey app advised and planned

## What happened
- /ak-advise compared CRM, Booking and a survey/forms app; the user chose a survey app for the Tenant's own customers, API only, with UI later.
- Scope went from forms to survey (name collision with HTML/Spring forms), then widened (file upload, quiz, computed variables, piping, i18n, analytics, builder API), then narrowed: survey only issues links and measures what happens after a link opens.
- Wrote plans/261116-0145-survey-app/ (10 phases) with a verification pass, a red-team self-review and validation.

## Verified findings that changed the design
- `SuiteActorResolver.java:59` resolves a service-client token to no actor, and only Tenant System tokens carry `tenant_id`. Survey calling mailroom over REST would have needed a core and mailroom change. The user moved invitations and reminders out of survey; resend is now a manual recipe using mailroom groups and an `in_group AND NOT in_group` segment, with no mailroom change.
- Platform builds the permission catalog from `contracts/permissions/*.yaml` and lists service clients in `application.yml`, so a new app needs no Platform Java change.
- Billing's webhook mechanics (signer, address policy, guarded DNS, HTTP sender, retry schedule) depend only on core-kernel, so they can move to `core/core-webhook`. Billing's `rotate-secret` has no overlap window.

## Decision
- Stable question ids across immutable versions; a JSON-AST expression language with shared test vectors in `contracts/survey/`; decimal numbers (scale 6, HALF_EVEN); a typed `answers` table for analytics; the cap and single use enforced by DB constraints; DB-IP Lite for GeoIP; public rate limits in memory per instance.

## Next steps
- Commit the mailroom rename (it blocks phase 3).
- Start with phases 1 and 2: contracts, then the golden signature test before the webhook extraction.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
