-- crm's one baseline: the roles, the row-level-security template, the Platform replica, the replica's own state, the
-- inbox and the transactional outbox, the webhook tables and the coordination tables of the runtime.
--
-- There is no business table yet. Customers, orders with lines and returns, activities, custom fields, tags and
-- consent arrive in later phases, each in its own reserved migration range so two phases cannot collide:
--
--   V10 customers and their batch upsert (phase 5)
--   V20 custom fields, tags and consent (phase 6)
--   V30 orders, lines and returns (phase 7)
--   V40 activity and the profile's read indexes (phase 8)
--   V50 erasure (phase 9)
--
-- A later phase creates its own file in its own range and never edits this one.
--
-- The migration owner (the role Flyway connects as) owns every table. The application connects as crm_app, which owns
-- nothing and cannot bypass row-level security, so a mis-wired connection fails closed instead of reading across
-- tenants.

do $$
begin
    if not exists (select from pg_roles where rolname = 'crm_app') then
        create role crm_app login password '${appDbPassword}'
            nosuperuser nocreatedb nocreaterole nobypassrls;
    end if;
end
$$;

grant usage on schema public to crm_app;
alter default privileges in schema public
    grant select, insert, update, delete on tables to crm_app;
alter default privileges in schema public
    grant usage, select on sequences to crm_app;

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

-- The five replica tables. This is a deliberate copy of core-replica's replica-tables.sql, which is a resource inside
-- that module rather than a migration (Flyway ignores a non-versioned resource name). A column the writer upserts and
-- this schema lacks is a test failure; a new core column needs a new migration here.
--
-- The replica is Platform-owned data, not tenant-owned rows this service writes. It carries no row-level security: the
-- read ports filter by tenant explicitly, and every query takes the tenant id from the caller's scope. RLS applies to
-- the tables crm itself writes, created below.
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

-- One row per (tenant, app): whether the tenant may use the app at all. The app code is `crm` for this deployable.
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
-- tenant must be recordable before that row exists. Row-level security on tenant_id remains the isolation guarantee.
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

-- The transactional outbox. crm has one consumer, in process: the webhook context records one delivery per subscribed
-- endpoint inside the relay's transaction. The broker relay's columns (publish_status, publish_attempts,
-- publish_next_at) are present anyway, because they belong to the shared table shape and a short copy fails on the
-- first statement that names one.
create table outbox (
    id               uuid        primary key,
    tenant_id        uuid        not null,
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

-- Outbound webhooks: the endpoints a Tenant registers, the deliveries they produce, and the attempt log.
--
-- Copied from the shipped webhook schema with its app-specific columns removed, as every app that delivers a webhook
-- does: a delivery stores the exact body that is signed and sent, so a retry is byte-identical. An in-flight delivery
-- keeps status IN_PROGRESS with next_attempt_at at the end of its lease, so a worker that dies mid-send lets the lease
-- run out and the row becomes due again.
create table webhook_endpoints (
    id                uuid        primary key,
    tenant_id         uuid        not null,
    url               text        not null check (length(url) between 1 and 2048),
    description       text        check (length(description) <= 500),
    event_types       text[]      not null check (cardinality(event_types) between 1 and 100),
    status            text        not null check (status in ('ACTIVE', 'DISABLED')),
    -- The signing secret is stored only as envelope-encrypted ciphertext and is never returned after creation.
    key_id            text        not null,
    secret_ciphertext text        not null,
    version           bigint      not null default 1 check (version >= 1),
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    secret_rotated_at timestamptz
);

create index webhook_endpoints_tenant_idx on webhook_endpoints (tenant_id, created_at desc);

call enable_tenant_rls('webhook_endpoints');

create table webhook_deliveries (
    id               uuid        primary key,
    tenant_id        uuid        not null,
    endpoint_id      uuid        not null references webhook_endpoints (id),
    event_id         uuid        not null,
    event_type       text        not null,
    body             text        not null,
    status           text        not null check (status in ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'DEAD')),
    attempts         integer     not null default 0 check (attempts >= 0),
    next_attempt_at  timestamptz not null,
    last_status_code integer,
    last_error       text,
    created_at       timestamptz not null,
    completed_at     timestamptz,
    check ((status in ('SUCCEEDED', 'DEAD')) = (completed_at is not null))
);

-- One delivery per event and endpoint, so a redelivered outbox row cannot send the same fact twice.
create unique index webhook_deliveries_event_idx on webhook_deliveries (endpoint_id, event_id);
create index webhook_deliveries_due_idx on webhook_deliveries (next_attempt_at)
    where status in ('PENDING', 'IN_PROGRESS');
create index webhook_deliveries_endpoint_idx on webhook_deliveries (endpoint_id, id desc);
-- Retention deletes a finished delivery by its completion time; without the index the purge scans the table.
create index webhook_deliveries_completed_idx on webhook_deliveries (completed_at)
    where status in ('SUCCEEDED', 'DEAD');

call enable_tenant_rls('webhook_deliveries');

-- The attempt log is append-only: what was tried and what came back is evidence.
create table webhook_delivery_attempts (
    id               uuid        primary key,
    tenant_id        uuid        not null,
    delivery_id      uuid        not null references webhook_deliveries (id),
    attempt_no       integer     not null check (attempt_no >= 1),
    status_code      integer,
    error            text,
    response_excerpt text,
    latency_ms       integer     not null check (latency_ms >= 0),
    attempted_at     timestamptz not null,
    unique (delivery_id, attempt_no)
);

call enable_tenant_rls('webhook_delivery_attempts');

-- An attempt is written once and never edited. Delete stays: housekeeping removes an attempt together with the
-- finished delivery whose retention expired, which is what keeps a delivery body and a response excerpt from living
-- forever.
revoke update on webhook_delivery_attempts from crm_app;

-- Coordination state shared by every instance. Nothing here is business data, and no row is locked while work runs: a
-- job lease is ownership by deadline, and a run record is claimed before the work. Each statement touches one row, so
-- these tables add no lock edge in front of a customer, an order or a delivery.
--
-- One row per singleton job. Acquiring, renewing and releasing are short independent statements; the owner is allowed
-- back in at any time and another instance may take over once the deadline passes.
create table job_leases (
    name        text        primary key,
    owner       text        not null,
    acquired_at timestamptz not null,
    expires_at  timestamptz not null
);

-- A job that must run at most once per key (a day, a period) claims the run before doing the work. The primary key is
-- the promise: a concurrent or repeated claim inserts nothing. The tenant is a plain id rather than a foreign key, so
-- claiming a run never takes a lock on a business row. No job of this phase needs it; a daily evaluation in a later
-- phase is the shape it is here for.
create table job_runs (
    job        text        not null,
    run_key    text        not null,
    tenant_id  uuid        not null,
    started_at timestamptz not null,
    primary key (job, run_key, tenant_id)
);
