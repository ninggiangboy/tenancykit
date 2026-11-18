-- What a Tenant sells: groups that stand in for a set of resources, offerings of either inventory kind, the
-- requirements an interval offering places on a booking, and the cancellation thresholds the Tenant names. No amounts
-- are stored: booking never touches money and a window carries only the Tenant's own code.
create table resource_groups (
    tenant_id    uuid        not null,
    id           uuid        primary key,
    code         text        not null check (length(code) between 1 and 60),
    name         text        not null check (length(name) between 1 and 200),
    -- How any-available picks a member: PRIORITY, ROUND_ROBIN, LEAST_BUSY or FIT_SEATS.
    strategy     text        not null check (strategy in ('PRIORITY', 'ROUND_ROBIN', 'LEAST_BUSY', 'FIT_SEATS')),
    max_combine  integer     not null default 1 check (max_combine between 1 and 20),
    -- The round-robin position, so the next assignment does not always start at the first member.
    cursor       text,
    version      bigint      not null default 1 check (version >= 1),
    created_at   timestamptz not null,
    updated_at   timestamptz not null,
    unique (tenant_id, code)
);

create index resource_groups_keyset_idx on resource_groups (tenant_id, created_at desc, id desc);

call enable_tenant_rls('resource_groups');

create table group_members (
    tenant_id   uuid    not null,
    group_id    uuid    not null references resource_groups (id) on delete cascade,
    resource_id uuid    not null references resources (id),
    priority    integer not null default 0 check (priority >= 0),
    primary key (tenant_id, group_id, resource_id)
);

create index group_members_resource_idx on group_members (tenant_id, resource_id);

call enable_tenant_rls('group_members');

create table offerings (
    tenant_id              uuid        not null,
    id                     uuid        primary key,
    code                   text        not null check (length(code) between 1 and 60),
    name                   text        not null check (length(name) between 1 and 200),
    -- INTERVAL claims a time range on a resource; CAPACITY claims places in a session pool.
    kind                   text        not null check (kind in ('INTERVAL', 'CAPACITY')),
    status                 text        not null check (status in ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    duration_min           integer     not null check (duration_min >= 1),
    -- Null means the duration is fixed at duration_min.
    duration_max           integer     check (duration_max is null or duration_max >= duration_min),
    duration_step          integer     not null default 1 check (duration_step >= 1),
    slot_step              integer     not null check (slot_step >= 1),
    buffer_before          integer     not null default 0 check (buffer_before >= 0),
    buffer_after           integer     not null default 0 check (buffer_after >= 0),
    lead_time_seconds      integer     not null default 0 check (lead_time_seconds >= 0),
    horizon_days           integer     not null check (horizon_days >= 1),
    hold_ttl_seconds       integer     check (hold_ttl_seconds is null or hold_ttl_seconds >= 1),
    max_qty                integer     not null default 1 check (max_qty >= 1),
    public_shows_remaining boolean     not null default false,
    queue_mode             text        not null default 'NONE' check (queue_mode in ('NONE', 'REQUIRED')),
    admit_per_second       integer     not null default 50 check (admit_per_second >= 1),
    admit_window_seconds   integer     not null default 120 check (admit_window_seconds >= 1),
    version                bigint      not null default 1 check (version >= 1),
    created_at             timestamptz not null,
    updated_at             timestamptz not null,
    unique (tenant_id, code)
);

create index offerings_keyset_idx on offerings (tenant_id, created_at desc, id desc);
create index offerings_kind_status_idx on offerings (tenant_id, kind, status);

call enable_tenant_rls('offerings');

-- One row per named slot. Exactly one of resource_id and group_id is set; the pair (offering, slot) is unique and the
-- application proves no resource is named twice across the requirements of one offering.
create table offering_requirements (
    tenant_id   uuid not null,
    offering_id uuid not null references offerings (id) on delete cascade,
    slot_code   text not null check (length(slot_code) between 1 and 60),
    resource_id uuid references resources (id),
    group_id    uuid references resource_groups (id),
    primary key (tenant_id, offering_id, slot_code),
    check ((resource_id is null) <> (group_id is null))
);

create index offering_requirements_resource_idx on offering_requirements (tenant_id, resource_id);
create index offering_requirements_group_idx on offering_requirements (tenant_id, group_id);

call enable_tenant_rls('offering_requirements');

create table cancellation_windows (
    tenant_id            uuid not null,
    offering_id          uuid not null references offerings (id) on delete cascade,
    minutes_before_start integer not null check (minutes_before_start >= 0),
    code                 text not null check (length(code) between 1 and 60),
    primary key (tenant_id, offering_id, minutes_before_start)
);

call enable_tenant_rls('cancellation_windows');
