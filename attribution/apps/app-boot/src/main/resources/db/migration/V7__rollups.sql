-- The rollups a report reads, plus the one cursor that says how far they have reached.
--
-- A report never scans raw events: the trailing three hours are recomputed every minute into an hourly traffic table, a
-- daily referrer table (kept apart so the hourly table's cardinality stays bounded) and a daily conversion/credit table.
-- Buckets are UTC because v1 offers no time zone in a report; a Tenant that needs a local day reads hourly data.
--
-- The rollup cursor is deployment-wide, not tenant data: it says the last UTC day every rollup has fully absorbed, which
-- is what lets retention drop a raw partition without deleting a day no report can answer. It carries no row-level
-- security for that reason. The rollup tables themselves are tenant tables and use the same policy as every other.

create table rollup_traffic_hourly (
    tenant_id      uuid        not null,
    bucket         timestamptz not null,
    link_id        uuid,
    campaign_id    uuid,
    destination_id uuid,
    device_class   text        not null,
    country        text,
    entry          text,
    traffic_class  text        not null check (traffic_class in ('HUMAN', 'BOT', 'SUSPECT')),
    clicks         bigint      not null default 0 check (clicks >= 0),
    page_views     bigint      not null default 0 check (page_views >= 0),
    sessions       bigint      not null default 0 check (sessions >= 0),
    visitor_days   bigint      not null default 0 check (visitor_days >= 0)
);

-- A dimension may be null (a click outside a campaign, a direct visit), so uniqueness is over the coalesced value: the
-- job deletes a bucket range and inserts the fresh rows, and this index keeps a partial insert from doubling a bucket.
create unique index rollup_traffic_hourly_key on rollup_traffic_hourly (
    tenant_id, bucket, coalesce(link_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(campaign_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(destination_id, '00000000-0000-0000-0000-000000000000'::uuid),
    device_class, coalesce(country, ''), coalesce(entry, ''), traffic_class);
create index rollup_traffic_hourly_bucket_idx on rollup_traffic_hourly (tenant_id, bucket);
call enable_tenant_rls('rollup_traffic_hourly');

-- Referrers are split from the hourly table because a referrer domain has much higher cardinality than a link: the top
-- fifty per link and day are kept, and the rest are folded into `other` so a daily table stays small.
create table rollup_referrers_daily (
    tenant_id       uuid   not null,
    day             date   not null,
    link_id         uuid,
    referrer_domain text   not null,
    clicks          bigint not null default 0 check (clicks >= 0)
);

create unique index rollup_referrers_daily_key on rollup_referrers_daily (
    tenant_id, day, coalesce(link_id, '00000000-0000-0000-0000-000000000000'::uuid), referrer_domain);
create index rollup_referrers_daily_range_idx on rollup_referrers_daily (tenant_id, day);
call enable_tenant_rls('rollup_referrers_daily');

-- One row per campaign, link, goal, model and currency per UTC day. `conversions` and `multi_touch_conversions` are
-- counts, but numeric because a credit can be fractional under the linear model; `credited_value` is the sum of the
-- credits. The currency is stored so a value metric can exclude a conversion whose currency differs from the campaign's
-- instead of adding unlike amounts.
create table rollup_conversions_daily (
    tenant_id                uuid           not null,
    day                      date           not null,
    campaign_id              uuid,
    link_id                  uuid,
    goal_id                  uuid,
    model                    text           check (model is null or model in ('FIRST', 'LAST', 'LINEAR')),
    currency                 text           not null check (currency ~ '^[A-Z]{3}$'),
    conversions              numeric(19, 4) not null default 0 check (conversions >= 0),
    credited_value           numeric(19, 4) not null default 0,
    multi_touch_conversions  numeric(19, 4) not null default 0 check (multi_touch_conversions >= 0)
);

create unique index rollup_conversions_daily_key on rollup_conversions_daily (
    tenant_id, day, coalesce(campaign_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(link_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(goal_id, '00000000-0000-0000-0000-000000000000'::uuid), coalesce(model, ''), currency);
create index rollup_conversions_daily_range_idx on rollup_conversions_daily (tenant_id, day);
call enable_tenant_rls('rollup_conversions_daily');

-- One row, updated in place. A deployment-wide cursor is deliberately not tenant-scoped: retention drops shared
-- partitions, so the safe day is the one every tenant's rollup has reached.
create table rollup_cursor (
    id                smallint primary key default 1 check (id = 1),
    rolled_up_through date,
    data_through      timestamptz
);

insert into rollup_cursor (id, rolled_up_through, data_through) values (1, null, null)
on conflict (id) do nothing;
