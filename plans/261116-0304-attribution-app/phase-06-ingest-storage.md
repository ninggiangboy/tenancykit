---
phase: 6
title: "Ingest storage, partitions and retention"
status: pending
priority: P1
effort: "L"
dependencies: [5]
---

# Phase 6: Ingest Storage, Partitions and Retention

## Goal

Click events published by phase 5 are stored exactly once, grouped by Tenant under row-level security, in day-partitioned
tables that are created ahead of time and dropped when they pass retention, with a worker that survives a bad row.

## Context

The app role owns nothing and cannot create a partition (`ops/postgres/init/00-databases.sh`), so partition DDL runs
through `SECURITY DEFINER` functions owned by `attribution_owner`. Batch consumption is new code: `core-messaging` dispatches
one envelope at a time for domain events (`AmqpInboundDispatcher`) and is not used here.

## Files to create / modify

- Create: migration `V3__click_events.sql`: `click_events` partitioned by range on `occurred_at` (primary key
  `(event_id, occurred_at)`; columns tenant_id, click_id, link_id, destination_id, campaign_id, device_class,
  browser_family, country, referrer_domain, entry, traffic_class `HUMAN|BOT|SUSPECT`, visitor_hash; index
  `(tenant_id, click_id)` and `(tenant_id, link_id, occurred_at)` per partition), a default partition, RLS on the parent,
  functions `attribution_ensure_partitions(table_name text, from_day date, days int)` and
  `attribution_drop_partitions(table_name text, before_day date)` (`SECURITY DEFINER`, fixed `search_path`, table name
  checked against an allow list), and `grant execute` to `attribution_app`
- Create: `attribution/contexts/tracking/.../ingest/` (`IngestConsumer`, `ClickWriter`, `PartitionMaintenance`)
- Create: `attribution/apps/app-boot/.../jobs/PartitionJob`, `RetentionJob` (`@Profile("worker")`)
- Modify: jOOQ code generation excludes `click_events_p*` and `click_events_default`; regenerate and commit

## Tasks

1. **Consumer.** A Spring AMQP batch listener on `attribution.ingest` (batch size 500, receive timeout 200 ms, manual ack
   after the transaction commits). Validate each message against `contracts/attribution/ingest/click.schema.json` and
   discard-with-count an invalid one.
2. **Per-tenant insert.** Group the batch by tenant; insert each group in its own tenant-scoped transaction (the
   `set_config('app.tenant_id')` hook the baseline provides) with jOOQ batch insert and `on conflict do nothing` on the
   primary key, so a redelivered batch writes nothing twice.
3. **Poison rows.** If a group fails, retry it three times with backoff, then bisect the group to isolate the failing row,
   store that row nowhere, publish it once to the DLQ and count it. The rest of the batch commits.
4. **Partitions.** `PartitionJob` runs hourly and ensures partitions for today plus seven days; health goes `DOWN` if
   tomorrow's partition is missing. The default partition catches strays and is checked by a metric (rows in default > 0
   is an alert).
5. **Retention.** Raw retention is deployment configuration (`ATTRIBUTION_RAW_RETENTION_DAYS`, default 90), not a Tenant
   setting, because partitions are shared across Tenants. `RetentionJob` drops partitions older than that, but never one
   whose day has not been rolled up (the rollup cursor from phase 9; until it exists the job drops nothing and says so).
6. **Metrics.** `attribution.ingest.consumed`, `.stored`, `.duplicate`, `.invalid`, `.dead`, consumer lag from the queue
   depth.
7. **Privacy gate.** A schema test fails the build if any column in any table is named or typed like an address
   (`ip`, `ip_address`, `inet`, `remote_addr`) or stores a user-agent string.

## Verification

- `cd attribution && ./gradlew test --tests '*ingest*' --tests '*partition*' --tests '*privacy*'` green.
- Tests (Testcontainers Postgres and RabbitMQ): 5,000 clicks across two tenants are all stored, each under its own tenant,
  and tenant A's token-scoped query sees none of tenant B's; publishing the same batch twice stores it once; one invalid
  row among 500 does not block the other 499 and ends on the DLQ; stopping the app mid-batch and restarting stores every
  event exactly once; partitions exist for the next seven days after the job; the app role can call the partition function
  but cannot `create table`; dropping a partition removes its rows.
- `ops` check: `psql` as `attribution_app` cannot create a partition directly.

## Risks

- Partitions and RLS: policies on the partitioned parent apply to queries through the parent, and the app only ever queries
  the parent; the RLS test runs through the parent and also asserts the app role has no direct grant on partitions.
- A bisect on a large poison batch is slow. The retry count and batch size are configuration; a metric exposes bisections.
