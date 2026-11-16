-- Roles, the row-level-security template, the Platform replica, the inbox, the outbox, the outbound webhook tables, the
-- job coordination tables and the replica's own state.
--
-- The migration owner (the role Flyway connects as) owns every table. The application connects as loyalty_app, which
-- owns nothing and cannot bypass row-level security, so a mis-wired connection fails closed instead of reading across
-- tenants.
--
-- Later migrations are reserved in tens so parallel work cannot collide: V10 the program, its tiers and its promotions;
-- V20 members, orders and the points ledger; V30 redemption and debt; V40 tier evaluation; V50 expiry and its notice;
-- V60 rewards and vouchers; V70 erasure and the retained messaging rows.

do $$
begin
    if not exists (select from pg_roles where rolname = 'loyalty_app') then
        create role loyalty_app login password '${appDbPassword}'
            nosuperuser nocreatedb nocreaterole nobypassrls;
    end if;
end
$$;

grant usage on schema public to loyalty_app;
alter default privileges in schema public
    grant select, insert, update, delete on tables to loyalty_app;
alter default privileges in schema public
    grant usage, select on sequences to loyalty_app;

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
-- the tables this service owns, created below.
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

-- The transactional outbox. Every fact loyalty reports is written here inside the transaction of the change and
-- relayed in process to the webhook module; loyalty declares no exchange, so the broker's own publish columns do not
-- exist. A relayed row is DELIVERED once every consumer has handled it.
create table outbox (
    id              uuid        primary key,
    tenant_id       uuid        not null,
    aggregate       text        not null,
    type            text        not null,
    payload         jsonb       not null,
    created_at      timestamptz not null,
    status          text        not null default 'PENDING' check (status in ('PENDING', 'DELIVERED', 'DEAD')),
    attempts        integer     not null default 0,
    next_attempt_at timestamptz not null,
    last_error      text,
    published_at    timestamptz
);

create index outbox_pending_idx on outbox (next_attempt_at) where status = 'PENDING';
call enable_tenant_rls('outbox');

-- Outbound webhooks: the endpoints a Tenant registers, the deliveries they produce and the attempt log.
--
-- A delivery stores the exact body that is signed and sent, so a retry is byte-identical. An in-flight delivery keeps
-- status IN_PROGRESS with next_attempt_at at the end of its lease, so a worker that dies mid-send simply lets the lease
-- run out and the row becomes due again.
create table webhook_endpoints (
    id                uuid        primary key,
    tenant_id         uuid        not null,
    url               text        not null check (length(url) between 1 and 2048),
    description       text        check (length(description) <= 255),
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

-- No foreign key to webhook_endpoints: an endpoint may be deleted while its delivery history stays, and a delivery
-- still owed to it is marked dead in the same transaction. The send path joins the live endpoint, so a delivery whose
-- endpoint is gone is never claimed.
create table webhook_deliveries (
    id               uuid        primary key,
    tenant_id        uuid        not null,
    endpoint_id      uuid        not null,
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

-- One unfinished delivery per event and endpoint, so a redelivered outbox row cannot queue a second attempt of the same
-- fact while the first is still owed. A resend of a finished delivery is a new row, which keeps the dead one as
-- evidence and lets the copy carry exactly the body the first attempt carried.
create unique index webhook_deliveries_event_idx on webhook_deliveries (endpoint_id, event_id)
    where status in ('PENDING', 'IN_PROGRESS');
create index webhook_deliveries_due_idx on webhook_deliveries (next_attempt_at)
    where status in ('PENDING', 'IN_PROGRESS');
create index webhook_deliveries_endpoint_idx on webhook_deliveries (endpoint_id, id desc);
create index webhook_deliveries_completed_idx on webhook_deliveries (completed_at)
    where status in ('SUCCEEDED', 'DEAD');

call enable_tenant_rls('webhook_deliveries');

-- The attempt log is append-only: what was tried and what came back is evidence. An attempt is never updated, and
-- housekeeping deletes a finished delivery's attempts with the delivery, which is why delete stays granted.
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

revoke update on webhook_delivery_attempts from loyalty_app;

-- Coordination state shared by every worker instance. Nothing here is business data and no row is locked while work
-- runs: a job lease is ownership by deadline, a run record is claimed before the work.
--
-- No tenant-scoped read goes through these tables: a job names its tenant in the row it claims, so they carry no
-- row-level security and no policy that a background scope would have to satisfy.
create table job_leases (
    name        text        primary key,
    owner       text        not null,
    acquired_at timestamptz not null,
    expires_at  timestamptz not null
);

-- A job that must run at most once per key (a day, a window) claims the run before doing the work. The primary key is
-- the promise: a concurrent or repeated claim inserts nothing. A run whose process died stays unfinished until its TTL
-- passes, so a later tick can take it over.
create table job_runs (
    job         text        not null,
    run_key     text        not null,
    tenant_id   uuid        not null,
    started_at  timestamptz not null,
    finished_at timestamptz,
    primary key (job, run_key, tenant_id)
);
