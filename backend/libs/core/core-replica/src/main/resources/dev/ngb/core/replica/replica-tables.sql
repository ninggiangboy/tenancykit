-- The replica tables every app that consumes platform.events must have. An app that already owns some of these
-- (the first app kept `tenants`, `tenant_domains` and `tenant_systems` from its own migrations) adds only what it
-- lacks. `ReplicaStream` is the one list of the streams that write them; this script is the one DDL source.
--
-- The columns are exactly what ReplicaWriter upserts; the unique key on each row is what makes the upsert idempotent.
-- Every table carries a monotonic `version` copied from the state-transfer event, so an older or duplicate message is
-- a no-op. Platform never hard-deletes these rows, so a rebuild is "upsert everything", never "truncate then load".
--
-- The owner is the migration role; the application role gets the grants below, so a mis-wired connection fails closed.

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

-- One row: when a full rebuild last finished every stream. `ReplicaBootstrap` reads it to decide whether the replica
-- is complete; a rebuild that starts (the first stream lands) but never finishes must be retried on the next boot,
-- which a row count per stream cannot tell apart from "already built".
--
-- No version column: this row is local state, not a replicated stream, so no event ever upserts into it.
create table if not exists replica_state (
    id         smallint primary key default 1,
    rebuilt_at timestamptz not null,
    check (id = 1)
);
