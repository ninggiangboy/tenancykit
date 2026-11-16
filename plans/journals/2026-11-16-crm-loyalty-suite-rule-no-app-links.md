---
title: CRM and loyalty plan revised under the no-app-links suite rule
date: 2026-11-16
summary: A second /ak-advise set the rule that suite apps never link to each other; loyalty now takes orders from the Tenant through its own API, crm sends signed webhooks instead of crm.events, and existing apps change in wording only
---

# CRM and loyalty plan revised under the no-app-links suite rule

## What happened
- The user reviewed plans/261116-1023-crm-loyalty-apps/ and set a direction: apps do not link to each other directly; the
  Tenant System links them, so each app stays optional and a Tenant keeps its choice of tools.
- A second /ak-advise interview fixed the details: crm sends signed webhooks to Tenant Systems (no `crm.events`); the
  existing apps get the rule written into contracts and docs, no code change; a member is enrolled by its first order or by
  `PUT /members/{externalRef}`; the end-to-end proof is a smoke script playing the Tenant, with the sample Tenant System
  integrated later; no backfill because the project is new.
- The plan index and phases 1, 2, 3, 4, 5, 7, 9, 11 and 16 were revised; the other seven phases had no crm-event dependency.

## Verified findings that shaped the revision
- No existing app consumes another app's business exchange. Billing consumes its own `billing.events`
  (`PostpaidCycleIntegrationTest.java:168`), so it stays; `survey.events` has no consumer. Only the docs wording "so a future
  app can react" contradicted the rule.
- Survey's outbox feeds webhooks in process through `OutboxRelay`; `BrokerRelay` is a separate bean. crm can copy
  `survey/contexts/webhook` and run with no exchange at all.
- `core-webhook` already has `allowNonPublicTargets`, so a compose-only forwarder can receive crm's webhooks.
- `ops/smoke/isolation.sh` checks publishes and queue reads but not bindings; phase 16 adds a bind check.

## Decision
- Removed from the plan: the consumer entitlement gate, `loyalty.crm-events`, `held_orders` and its release job,
  `orderVersion` handling in loyalty, members created from `customer.created`, the consumer-extension test, and the
  constraint that line items live in crm only.
- loyalty stores the order lines that earn and returns read, uses crm's field names so a forwarder is a field mapping, and
  answers a return before its order with `422` instead of holding it.

## Next steps
- Run phase 1, which now also writes the suite rule; then phase 2 (check mailroom's service client first); then phases 3
  and 4 in parallel. After the skeletons the crm and loyalty chains do not wait for each other.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
