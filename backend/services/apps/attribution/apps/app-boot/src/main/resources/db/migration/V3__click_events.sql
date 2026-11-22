-- The click events the redirect publishes, stored exactly once, grouped by tenant under row-level security.
--
-- The table is partitioned by day on `occurred_at`, because retention drops a partition rather than deleting rows.
-- The primary key includes the partition key, which PostgreSQL requires of a partitioned table; idempotency is still
-- on `event_id` within a day, and the consumer's insert is `on conflict (event_id, occurred_at) do nothing`.
--
-- The app role owns nothing and cannot create a partition, so partition DDL lives in two SECURITY DEFINER functions
-- owned by the migration owner. Their search_path is fixed and the table name is checked against an allow list, so a
-- composed name cannot reach another table. A default partition catches a stray row and is what the health check
-- watches: a row in it means a partition was missing at insert time.
--
-- The stored facts are the classified ones: a device class, a browser family, a country, a referrer domain and the
-- daily visitor hash. There is deliberately no address and no user-agent column; a later migration that adds one fails
-- the privacy test.

create table click_events (
    event_id        uuid        not null,
    occurred_at     timestamptz not null,
    tenant_id       uuid        not null,
    click_id        uuid        not null,
    link_id         uuid        not null,
    destination_id  uuid,
    campaign_id     uuid,
    device_class    text        not null check (device_class in ('DESKTOP', 'MOBILE', 'TABLET', 'UNKNOWN')),
    browser_family  text        not null check (length(browser_family) <= 32),
    country         text        check (country is null or country ~ '^[A-Z]{2}$'),
    referrer_domain text        check (referrer_domain is null or length(referrer_domain) <= 253),
    entry           text        check (entry is null or length(entry) <= 32),
    traffic_class   text        not null check (traffic_class in ('HUMAN', 'BOT', 'SUSPECT')),
    visitor_hash    text        not null check (length(visitor_hash) between 1 and 64),
    primary key (event_id, occurred_at)
) partition by range (occurred_at);

-- The parent carries the policy; a query through the parent applies it to every partition.
call enable_tenant_rls('click_events');

-- A stray row (a click at midnight before its partition existed) lands here rather than failing the insert. A row in it
-- is an alert, not a data source: the partition job catches up on its next sweep.
create table click_events_default partition of click_events default;

-- The app role inserts and reads through the parent; it never owns a partition.
grant select, insert on click_events to attribution_app;
grant select, insert on click_events_default to attribution_app;

-- The tables whose partitions these functions may manage. A name outside this list is refused, so a caller cannot use
-- the function to reach a table it was not written for.
create table partition_managed_tables (
    table_name text primary key
);

insert into partition_managed_tables (table_name) values ('click_events');

-- Creates the partition for each day in [p_from_day, p_from_day + p_days) if it is missing, with the parent's indexes.
create function attribution_ensure_partitions(p_table text, p_from_day date, p_days int)
    returns int
    language plpgsql
    security definer
    set search_path = public, pg_temp
as $$
declare
    created int := 0;
    managed boolean;
    target_day date;
    partition_name text;
    index_name text;
begin
    select exists (select 1 from partition_managed_tables m where m.table_name = p_table) into managed;
    if p_table is null or not managed then
        raise exception 'attribution_ensure_partitions: % is not a managed table', p_table;
    end if;
    if p_days is null or p_days < 1 or p_days > 366 then
        raise exception 'attribution_ensure_partitions: days must be between 1 and 366';
    end if;
    for i in 0 .. p_days - 1 loop
        target_day := p_from_day + i;
        partition_name := p_table || '_p' || to_char(target_day, 'YYYY_MM_DD');
        if to_regclass(partition_name) is null then
            execute format(
                'create table %I partition of %I for values from (%L) to (%L)',
                partition_name, p_table, target_day, target_day + 1);
            index_name := partition_name || '_click_idx';
            execute format(
                'create index %I on %I (tenant_id, click_id)', index_name, partition_name);
            index_name := partition_name || '_link_idx';
            execute format(
                'create index %I on %I (tenant_id, link_id, occurred_at)', index_name, partition_name);
            execute format('grant select, insert on %I to attribution_app', partition_name);
            created := created + 1;
        end if;
    end loop;
    return created;
end
$$;

-- Drops every partition of the table whose whole range ends before `p_before_day`. The default partition is never
-- dropped, and a child whose name does not parse as a managed day is left alone.
create function attribution_drop_partitions(p_table text, p_before_day date)
    returns int
    language plpgsql
    security definer
    set search_path = public, pg_temp
as $$
declare
    dropped int := 0;
    child_name text;
    managed boolean;
    target_day date;
    prefix_len int;
begin
    select exists (select 1 from partition_managed_tables m where m.table_name = p_table) into managed;
    if p_table is null or not managed then
        raise exception 'attribution_drop_partitions: % is not a managed table', p_table;
    end if;
    prefix_len := length(p_table) + 3;
    for child_name in
        select c.relname
        from pg_inherits i
        join pg_class c on c.oid = i.inhrelid
        join pg_class p on p.oid = i.inhparent
        join pg_namespace n on n.oid = p.relnamespace
        where p.relname = p_table
          and n.nspname = 'public'
          and c.relname like p_table || '\_p%'
    loop
        begin
            target_day := to_date(substring(child_name from prefix_len), 'YYYY_MM_DD');
        exception when others then
            -- A child whose name is not a managed day (a hand-made one, say) is not this function's to drop.
            continue;
        end;
        if target_day + 1 <= p_before_day then
            execute format('drop table if exists %I', child_name);
            dropped := dropped + 1;
        end if;
    end loop;
    return dropped;
end
$$;

grant execute on function attribution_ensure_partitions(text, date, int) to attribution_app;
grant execute on function attribution_drop_partitions(text, date) to attribution_app;

-- Create the partitions this deployment needs from the start, so the first click after migration has somewhere to go
-- even before the job's first tick.
select attribution_ensure_partitions('click_events', current_date, 8);
