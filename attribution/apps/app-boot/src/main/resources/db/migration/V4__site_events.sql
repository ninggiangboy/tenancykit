-- The site collection: a page view after a tracked click, a session, and the binding from a visitor to the clicks that
-- brought them.
--
-- Every table keeps only the classified facts and the identifiers a visitor consented to: a page view may carry a click
-- id and a visitor id, a session carries a visitor id and a click id, and a binding is the pair. There is no address and
-- no user-agent column; a later migration that adds one fails the privacy test.
--
-- page_events is partitioned by day like click_events, for the same reason: retention drops a partition rather than
-- deleting rows. sessions and visitor_clicks are not partitioned: they are bounded by the visitor's own activity and are
-- removed by the erasure endpoint, not by retention.

create table page_events (
    event_id     uuid        not null,
    occurred_at  timestamptz not null,
    tenant_id    uuid        not null,
    click_id     uuid,
    session_id   text,
    visitor_id   text,
    path         text        not null check (length(path) <= 2048),
    traffic_class text       not null check (traffic_class in ('HUMAN', 'BOT', 'SUSPECT')),
    primary key (event_id, occurred_at)
) partition by range (occurred_at);

call enable_tenant_rls('page_events');
create table page_events_default partition of page_events default;
grant select, insert, delete on page_events to attribution_app;
grant select, insert, delete on page_events_default to attribution_app;

-- One row per (tenant, session): the session's visitor and the click that started it, extended by every page view.
create table sessions (
    tenant_id        uuid        not null,
    session_id       text        not null check (length(session_id) between 1 and 128),
    visitor_id       text        not null check (length(visitor_id) between 1 and 128),
    click_id         uuid,
    started_at       timestamptz not null,
    last_activity_at timestamptz not null,
    page_count       integer     not null default 0 check (page_count >= 0),
    primary key (tenant_id, session_id)
);

call enable_tenant_rls('sessions');
create index sessions_visitor_idx on sessions (tenant_id, visitor_id);
grant select, insert, update, delete on sessions to attribution_app;

-- The only link between a visitor and the clicks that brought them: one row per (tenant, visitor, click). This is what
-- the multi-touch chain in a later phase reads, and what erasure removes.
create table visitor_clicks (
    tenant_id  uuid        not null,
    visitor_id text        not null check (length(visitor_id) between 1 and 128),
    click_id   uuid        not null,
    bound_at   timestamptz not null,
    primary key (tenant_id, visitor_id, click_id)
);

call enable_tenant_rls('visitor_clicks');
create index visitor_clicks_click_idx on visitor_clicks (tenant_id, click_id);
grant select, insert, delete on visitor_clicks to attribution_app;

-- page_events joins the managed tables so the partition job keeps it a week ahead too.
insert into partition_managed_tables (table_name) values ('page_events');

-- The ensure function is replaced so the per-partition indexes it adds are the ones the table actually has columns for:
-- a click event indexes by link as well, a page view has no link. The function's signature and its allow-list check are
-- unchanged, so V3's grants and the app role's call still hold.
create or replace function attribution_ensure_partitions(p_table text, p_from_day date, p_days int)
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
    has_link boolean;
begin
    select exists (select 1 from partition_managed_tables m where m.table_name = p_table) into managed;
    if p_table is null or not managed then
        raise exception 'attribution_ensure_partitions: % is not a managed table', p_table;
    end if;
    if p_days is null or p_days < 1 or p_days > 366 then
        raise exception 'attribution_ensure_partitions: days must be between 1 and 366';
    end if;
    select exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = p_table and column_name = 'link_id'
    ) into has_link;
    for i in 0 .. p_days - 1 loop
        target_day := p_from_day + i;
        partition_name := p_table || '_p' || to_char(target_day, 'YYYY_MM_DD');
        if to_regclass(partition_name) is null then
            execute format(
                'create table %I partition of %I for values from (%L) to (%L)',
                partition_name, p_table, target_day, target_day + 1);
            execute format(
                'create index %I on %I (tenant_id, click_id)', partition_name || '_click_idx', partition_name);
            if has_link then
                execute format(
                    'create index %I on %I (tenant_id, link_id, occurred_at)',
                    partition_name || '_link_idx', partition_name);
            end if;
            execute format(
                'create index %I on %I (tenant_id, occurred_at)',
                partition_name || '_tenant_idx', partition_name);
            execute format('grant select, insert, delete on %I to attribution_app', partition_name);
            created := created + 1;
        end if;
    end loop;
    return created;
end
$$;

-- The migration's own first week for the new table.
select attribution_ensure_partitions('page_events', current_date, 8);
