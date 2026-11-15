-- Roles, the row-level-security template, the Platform replica, the inbox and the replica's own state.
--
-- The migration owner (the role Flyway connects as) owns every table. The application connects as notification_app,
-- which owns nothing and cannot bypass row-level security, so a mis-wired connection fails closed instead of reading
-- across tenants.

do $$
begin
    if not exists (select from pg_roles where rolname = 'notification_app') then
        create role notification_app login password '${appDbPassword}'
            nosuperuser nocreatedb nocreaterole nobypassrls;
    end if;
end
$$;

grant usage on schema public to notification_app;
alter default privileges in schema public
    grant select, insert, update, delete on tables to notification_app;
alter default privileges in schema public
    grant usage, select on sequences to notification_app;

-- The tenant of the current transaction. set_config(..., true) is transaction-local, and after the first use
-- PostgreSQL reports an unset value as '' rather than NULL, hence the nullif.
create function app_tenant_id() returns uuid
    language sql stable
as $$ select nullif(current_setting('app.tenant_id', true), '')::uuid $$;

-- Every tenant-scoped table calls this once. FORCE applies the policy to the table owner too.
create procedure enable_tenant_rls(tbl regclass)
    language plpgsql
as $$
begin
    execute format('alter table %s enable row level security', tbl);
    execute format('alter table %s force row level security', tbl);
    execute format(
        'create policy tenant_isolation on %s using (tenant_id = app_tenant_id()) with check (tenant_id = app_tenant_id())',
        tbl);
end
$$;

-- The five replica tables. This is a deliberate copy of core-replica's replica-tables.sql, which is a resource
-- inside that module rather than a migration (Flyway ignores a non-versioned resource name). A column the writer
-- upserts and this schema lacks is a Phase 3 test failure; a new core column needs a new migration here.
--
-- The replica is Platform-owned data, not tenant-owned rows this service writes. It carries no row-level security:
-- the read ports filter by tenant explicitly, and every query takes the tenant id from the caller's scope. RLS does
-- apply to the dispatch tables created in later migrations.
create table if not exists tenants (
    id         uuid primary key,
    code       text        not null unique,
    name       text        not null,
    status     text        not null check (status in ('ACTIVE', 'DEACTIVATED')),
    created_at timestamptz not null,
    version    bigint      not null default 0
);

create table if not exists tenant_domains (
    id         uuid primary key,
    tenant_id  uuid references tenants (id),
    hostname   text        not null unique,
    kind       text        not null check (kind in ('PLATFORM', 'TENANT_SUBDOMAIN')),
    created_at timestamptz not null,
    version    bigint      not null default 0,
    check ((kind = 'PLATFORM') = (tenant_id is null))
);

create index if not exists tenant_domains_tenant_idx on tenant_domains (tenant_id);

create table if not exists tenant_systems (
    id         uuid primary key,
    tenant_id  uuid        not null references tenants (id),
    name       text        not null,
    status     text        not null check (status in ('ACTIVE', 'DISABLED')),
    created_at timestamptz not null,
    version    bigint      not null default 0
);

create unique index if not exists tenant_systems_name_idx on tenant_systems (tenant_id, lower(name));

-- One row per user: the identity Keycloak authenticates and the permission codes this app enforces for them.
create table if not exists user_grants (
    user_id          uuid primary key,
    tenant_id        uuid        not null references tenants (id),
    scope            text        not null check (scope in ('PLATFORM', 'TENANT')),
    keycloak_subject text        not null unique,
    email            text        not null,
    display_name     text        not null,
    status           text        not null check (status in ('ACTIVE', 'DISABLED')),
    permissions      text[]      not null default '{}',
    version          bigint      not null default 0
);

create index if not exists user_grants_tenant_idx on user_grants (tenant_id);

-- One row per (tenant, app): whether the tenant may use the app at all.
create table if not exists app_entitlements (
    tenant_id uuid    not null references tenants (id),
    app_code  text    not null,
    enabled   boolean not null,
    version   bigint  not null default 0,
    primary key (tenant_id, app_code)
);

-- The Platform owns its own data as a reserved tenant. The outbox references tenants, and a fresh database must not
-- wait for the snapshot stream to deliver this row: `replica_state` and the readiness check need a usable tenant
-- before any live event can arrive.
insert into tenants (id, code, name, status, created_at)
values ('00000000-0000-7000-8000-000000000001', 'platform', 'Platform', 'ACTIVE', now());

-- The consumer's deduplication table, exactly as core-messaging's schema contract specifies.
--
-- No foreign key on tenant_id. `tenant.changed` is what creates the tenant row, so the first event for a brand-new
-- tenant must be recordable before that row exists; Billing shipped the foreign key and removed it. Row-level
-- security on tenant_id remains the isolation guarantee.
create table event_inbox (
    tenant_id    uuid        not null,
    consumer     text        not null,
    event_id     uuid        not null,
    processed_at timestamptz not null default now(),
    primary key (tenant_id, consumer, event_id)
);

create index event_inbox_processed_idx on event_inbox (processed_at);
call enable_tenant_rls('event_inbox');

-- Local rebuild state, not a replicated stream: one row says when a full rebuild last finished every stream.
create table replica_state (
    id         smallint primary key default 1,
    rebuilt_at timestamptz not null,
    check (id = 1)
);
