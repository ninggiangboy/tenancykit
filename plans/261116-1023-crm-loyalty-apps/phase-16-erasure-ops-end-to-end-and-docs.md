---
phase: 16
title: "loyalty erasure, ops seams, end-to-end and docs"
status: pending
priority: P1
effort: "L"
dependencies: [9, 13, 14, 15]
---

# Phase 16: loyalty Erasure, Ops Seams, End-to-End and Docs

## Goal

loyalty erases a member on the Tenant's call, both apps run in compose and the deployment pipeline, one end-to-end test plays
the Tenant System and proves that the Tenant alone connects crm and loyalty, and the docs describe what exists and what the
apps cannot promise.

## Context

The out-of-tree touchpoints are listed by `git grep -l -i mailroom -- . ':!mailroom' ':!plans' ':!docs' ':!attribution'
':!survey'` (re-run it now, and also `git grep -l -i "attribution\|survey" -- ops .github docker-compose.yml` to see what the
other plans have already added). Phase 2 already did databases, broker users, Platform clients and Keycloak mappers; this phase
does the rest. Survey and attribution may be editing the same files: edit append-only and keep both sides on conflict. Tenant
hosts reach apps through Caddy prefixes (`ops/caddy/Caddyfile`); there is no public (token-free) surface in crm or loyalty.

## Files to create or modify

- loyalty: `loyalty/contexts/points/.../erasure/*`, `V70__erasure.sql` (`erasure_requests`) with the jOOQ schema
  regenerated, tests
- `docker-compose.yml` (services `crm-migrate`, `crm-api`, `crm-worker`, `loyalty-migrate`, `loyalty-api`, `loyalty-worker`, with
  healthchecks like mailroom's and the next free host ports), `ops/caddy/Caddyfile`, `ops/smoke/isolation.sh`, `.env.example`
- `.github/workflows/{crm,loyalty}.yml`, entries in `suite.yml`, `core.yml`, `deploy.yml`
- `ops/terraform/{crm,loyalty}/` and the shared files mailroom touches (`ecs/ecr.tf`, `main.tf`, `locals.tf`, `variables.tf`,
  `outputs.tf`, `mq/users.tf`, `mq/variables.tf`, `rds/databases-roles.tf`, `rds/init-databases.sh`, `secrets/secrets.tf`,
  `README.md`)
- `docs/crm.md`, `docs/loyalty.md`, `docs/runtime-profiles.md`, `docs/architecture.md`, `docs/suite-messaging.md`,
  `docs/overview-and-scope.md`, root `README.md`, `contracts/README.md`, `contracts/messaging/README.md`
- `ops/smoke/crm-loyalty.sh`, following the existing smoke style, and `ops/smoke/tenant-forwarder/` (a stdlib-only HTTP
  receiver of about 40 lines with its own Dockerfile, started only under a `smoke` compose profile) that verifies crm's
  signature and maps `order.recorded`, `order.refunded` and `customer.erased` onto loyalty's endpoints. It is test
  scaffolding standing in for a Tenant's own code, not a suite service; the sample Tenant System stays unchanged

## Tasks

1. **Loyalty erasure.** `POST /members/{externalRef}/erasure` (`loyalty:member:erase`), idempotent like crm's: an
   `erasure_requests(tenant_id, ref_hash, member_id, erased_at)` row with `ref_hash = sha256(tenant_id ‖ external_ref)` makes
   a retry a replay; an unknown ref is `404`. In one transaction, member lock first: set `status` `ERASED`, `external_ref`
   and `display_name` null; void every `ISSUED` voucher and return its stock; write an `ERASE` entry per lot with `remaining
   > 0` and zero them; keep the ledger, lots, orders and `order_earns` as anonymous records keyed by the opaque member id.
   The old ref reaches no member afterwards. In the same transaction write one `voucher.voided` with `ERASURE` per voided
   voucher and then `member.erased`; these payloads carry `memberId` and no `externalRef`, so a Tenant correlates them by the
   member id earlier events gave it. Add `PersonalDataColumnsTest` for loyalty with the same classification rule as crm; it
   lists the outbox payload and webhook delivery body columns as "purged by housekeeping", as crm's does.
2. **Compose.** Add the six services, wired to the databases and broker users of phase 2. `docker compose up -d --wait`
   reaches healthy for every service.
3. **Proxy.** In `Caddyfile`, route `/crm/*` and `/loyalty/*` on every host to their `-api` services with the prefix stripped,
   before Billing's catch-all and in the style of the `/mailroom/*` rule. No logic in the proxy.
4. **CI and deploy.** Workflows for both apps and the shared entries; Terraform for both apps (image repositories, task
   definitions for api, worker and migrate, ALB rules for `/crm/*` and `/loyalty/*`, secrets) following mailroom's directory;
   `terraform validate` passes in `ops/terraform`.
5. **Isolation smoke.** `ops/smoke/isolation.sh`: `crm` and `loyalty` can publish to no exchange; neither can bind a queue to
   `billing.events`, `survey.events`, `mailer.events` or `platform.events` under a queue name that is not its own (binding
   needs `read` on the source exchange, `ops/rabbitmq/declare-users.sh:8-11`; the script today checks only publish and queue
   reads, so add a `require_refused` bind helper through the management API in the style of the existing ones); `crm_app`
   and `loyalty_app` cannot connect to any other app's database and no other role can connect to theirs.
6. **End-to-end** (`ops/smoke/crm-loyalty.sh` against compose, the script playing the Tenant System): create a Tenant with the
   `crm` and `loyalty` entitlements and a Tenant System credential with the new scopes; configure a program (unit 1,000), two
   tiers, one promotion (x2 on a category) and one reward.
   - **Dual-write path:** upsert a customer in crm and enrol the same ref in loyalty; send one order to both apps and see the
     promotion applied in loyalty's answer; send a return to both and see the clawback; redeem points and reverse the
     redemption; spend points, return the order, see the debt and the `409` on redemption, earn and see the debt repaid;
     redeem the reward and use its voucher twice (second is `409`); advance expiry (a short `expiry_months` or the job's
     clock) and see the lot expire.
   - **Forwarding path:** register the forwarder as crm's webhook endpoint for a second customer; record an order and a
     return in crm only, and see loyalty's balance match what the dual-write path gives for the same order within 5 seconds;
     erase the customer in crm and see the forwarder's `customer.erased` call anonymize the member and void its voucher.
   - **loyalty webhooks:** register the smoke receiver as a loyalty webhook endpoint subscribed to every type before the
     dual-write path, and assert it received, signed, each type that path produced (enrolled, earned, clawed back, redeemed,
     reversed, expiring (with a notice window wider than the short `expiry_months`), expired, voucher issued and used, and `tier.changed` if a tier was crossed).
   - A second Tenant sees none of it.
   - **Independence:** stop `crm-api` and `crm-worker` and run the dual-write steps against loyalty alone; stop
     `loyalty-api` and `loyalty-worker` and record an order in crm; assert Billing, mailroom, survey and attribution (those
     that exist) answer their health and one real call exactly as before.
7. **Permission coverage.** For each app a test asserts every code in its permission file is enforced by at least one endpoint,
   and every endpoint requires one.
8. **Docs.** Finish `docs/crm.md` and `docs/loyalty.md`: purpose, vocabulary, runtimes, contexts, data model, identity and
   idempotency rules, crm's webhook contract, the earn function with a worked example, the lock order, the debt and expiry
   rules, what each metric and limit means, and how a Tenant connects the two apps (dual-write, or forwarding crm's
   `order.*` and `customer.erased` webhooks, each with a short recipe and the retry rule), and how a Tenant fulfils a reward
   in another system, with the Billing coupon recipe as the example (on the `voucher.issued` webhook, look up the voucher's
   `rewardId` in the Tenant's own mapping, grant in the other system with an
   idempotency key derived from the voucher id, use the voucher last, reverse the reward redemption when the other system
   refuses for good; the same recipe is in `docs/coupons.md`). The v1 limits: no backfill or
   historic import of orders into loyalty; erasing in crm does not erase in loyalty unless the Tenant calls or forwards it;
   an expired lot stays expired when a late reversal restores into it. Runbooks: dead-letter, webhook deliveries in `DEAD`,
   expiry and tier job health, erasure and its limits for backups and logs. If a file passes 800 lines, split the Tenant guide into `docs/crm-guide.md` or
   `docs/loyalty-guide.md`. Update the other docs listed above; count deployables with `grep -i` at the time, not from plans.
9. **Diff gate.** `git diff --stat <base>..HEAD` for the whole delivery shows no file under `platform/**/*.java`, `billing/`,
   `mailroom/`, `survey/`, `attribution/`, `mailer/`, `notification/`, `tenant/`, `core/` or `SuiteActorResolver.java`. No
   file changes outside `crm/`, `loyalty/`, `contracts/`, `ops/`, `.github/`, `docs/`, `docker-compose.yml`, `.env.example`,
   `README.md` and Platform's `application.yml`. `git grep -n "crm.events"` over the whole tree finds nothing
   outside `plans/`.

## Verification

- `docker compose up -d --wait`, `ops/smoke/isolation.sh` and `ops/smoke/crm-loyalty.sh` (with the `smoke` profile) pass.
- All builds green: `core`, `platform`, `billing`, `mailroom`, `notification`, `mailer`, `tenant`, `crm`, `loyalty` (and
  `survey`, `attribution` if they build at that time).
- `terraform validate` passes; the diff gate holds.
- Back up any shared environment's databases before the first deploy migration runs.

## Risks

- The end-to-end test touches timing (5 seconds, expiry): drive time through settings, not sleeps, and poll with a deadline.
- A shared-file conflict with the survey or attribution ops phases: resolve by keeping both additions, never by dropping theirs.
- The forwarder only works because the compose profile sets crm's `allowNonPublicTargets`; the setting stays `false` in
  every deployed environment (`ops/terraform` never sets it), and a test of the default settings asserts that.
- crm without loyalty and loyalty without crm are both normal configurations; nothing checks for the other app.
