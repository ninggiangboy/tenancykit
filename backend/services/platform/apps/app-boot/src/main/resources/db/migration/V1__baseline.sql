-- Platform owns the suite's tenants and their app entitlements. Every table here is Platform's own; no other
-- service database is reachable from this one.
--
-- The migration owner (the role Flyway connects as) owns every table. The application connects as platform_app,
-- which owns nothing and cannot bypass row-level security, so a mis-wired connection fails closed.

do $$
begin
    if not exists (select from pg_roles where rolname = 'platform_app') then
        create role platform_app login password '${appDbPassword}'
            nosuperuser nocreatedb nocreaterole nobypassrls;
    end if;
end
$$;

grant usage on schema public to platform_app;
alter default privileges in schema public
    grant select, insert, update, delete on tables to platform_app;
alter default privileges in schema public
    grant usage, select on sequences to platform_app;

create function app_tenant_id() returns uuid
    language sql stable
as $$ select nullif(current_setting('app.tenant_id', true), '')::uuid $$;

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

-- Readable without a tenant: the hostname must be resolved to a tenant before any tenant context exists.
create table tenants (
    id         uuid primary key,
    code       text        not null unique check (code ~ '^[a-z][a-z0-9-]{1,38}[a-z0-9]$'),
    name       text        not null check (length(name) between 1 and 200),
    status     text        not null check (status in ('ACTIVE', 'DEACTIVATED')),
    created_at timestamptz not null,
    version    bigint      not null default 1
);

create table tenant_domains (
    id         uuid primary key,
    tenant_id  uuid references tenants (id),
    hostname   text        not null unique check (hostname = lower(hostname)),
    kind       text        not null check (kind in ('PLATFORM', 'TENANT_SUBDOMAIN')),
    created_at timestamptz not null,
    version    bigint      not null default 1,
    check ((kind = 'PLATFORM') = (tenant_id is null))
);

create index tenant_domains_tenant_idx on tenant_domains (tenant_id);

-- Entitlements are Platform-scope data but carry the tenant's own id, so the same RLS template applies.
create table app_entitlements (
    tenant_id  uuid        not null references tenants (id),
    app_code   text        not null,
    enabled    boolean     not null,
    updated_at timestamptz not null,
    version    bigint      not null default 1,
    primary key (tenant_id, app_code)
);

-- The outbox, inbox and audit log satisfy core-messaging's documented table contract.
create table outbox (
    id               uuid primary key,
    tenant_id        uuid        not null references tenants (id),
    aggregate        text        not null,
    type             text        not null,
    payload          jsonb       not null,
    created_at       timestamptz not null,
    status           text        not null default 'PENDING' check (status in ('PENDING', 'DELIVERED', 'DEAD')),
    attempts         integer     not null default 0,
    next_attempt_at  timestamptz not null,
    last_error       text,
    published_at     timestamptz,
    publish_status   text        not null default 'PENDING'
        check (publish_status in ('PENDING', 'PUBLISHED', 'DEAD', 'SKIPPED')),
    publish_attempts integer     not null default 0,
    publish_next_at  timestamptz not null default now()
);

create index outbox_pending_idx on outbox (next_attempt_at) where status = 'PENDING';
create index outbox_publish_pending_idx on outbox (publish_next_at)
    where published_at is null and publish_status = 'PENDING';
call enable_tenant_rls('outbox');

-- Platform writes events about every tenant (creating one, changing its domains, enabling an app), so the
-- platform scope may read and write every outbox row. Policies are OR'ed, so a tenant still sees only its own.
create policy platform_writer on outbox
    using (app_tenant_id() = '00000000-0000-7000-8000-000000000001')
    with check (app_tenant_id() = '00000000-0000-7000-8000-000000000001');

create table event_inbox (
    tenant_id    uuid        not null references tenants (id),
    consumer     text        not null,
    event_id     uuid        not null,
    processed_at timestamptz not null default now(),
    primary key (tenant_id, consumer, event_id)
);

call enable_tenant_rls('event_inbox');

create table audit_log (
    id          uuid primary key,
    tenant_id   uuid        not null references tenants (id),
    actor       text        not null,
    action      text        not null,
    subject     text        not null,
    request_id  text,
    occurred_at timestamptz not null
);

create index audit_log_tenant_idx on audit_log (tenant_id, occurred_at);
call enable_tenant_rls('audit_log');
revoke update, delete on audit_log from platform_app;

create table job_leases (
    name        text        primary key,
    owner       text        not null,
    acquired_at timestamptz not null,
    expires_at  timestamptz not null
);

create table job_runs (
    job         text        not null,
    run_key     text        not null,
    tenant_id   uuid        not null,
    started_at  timestamptz not null,
    finished_at timestamptz,
    primary key (job, run_key, tenant_id)
);

-- The Platform owns its own data as a reserved tenant, so platform-scope rows use the same RLS template as every
-- other tenant-scoped row. Billing's replica consumer needs this row too.
insert into tenants (id, code, name, status, created_at, version)
values ('00000000-0000-7000-8000-000000000001', 'platform', 'Platform', 'ACTIVE', now(), 1);
