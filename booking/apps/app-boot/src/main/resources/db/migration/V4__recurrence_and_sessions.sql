-- Recurrence, closed periods and materialized capacity sessions.
--
-- Nothing here stores a resolved instant for a rule: a series keeps its rule lines, its zone and its local start, and
-- the expander resolves them at the moment it needs instants. Only the occurrences inside the horizon become session
-- rows, and those rows are what a booking will later claim places from.
create table blocks (
    tenant_id        uuid        not null,
    id               uuid        primary key,
    resource_id      uuid        not null references resources (id) on delete cascade,
    reason           text        not null check (length(reason) between 1 and 200),
    -- A one-off block carries its two instants and an empty rule; a recurring one carries the rule and the occurrence
    -- duration instead, and `ends_at - starts_at` is that duration.
    starts_at        timestamptz,
    ends_at          timestamptz,
    rule             text[],
    duration_minutes integer     not null check (duration_minutes > 0),
    version          bigint      not null default 1 check (version >= 1),
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    check ((rule is null) = (starts_at is null and ends_at is null))
);

create index blocks_resource_idx on blocks (tenant_id, resource_id, starts_at);

call enable_tenant_rls('blocks');

create table session_series (
    tenant_id        uuid        not null,
    id               uuid        primary key,
    offering_id      uuid        not null references offerings (id),
    -- The IANA zone the rule is read in; the local start is wall-clock and carries no offset.
    zone             text        not null check (length(zone) between 1 and 64),
    dtstart          text        not null,
    rule             text[]      not null,
    duration_minutes integer     not null check (duration_minutes > 0),
    capacity         integer     not null check (capacity >= 1),
    status           text        not null check (status in ('ACTIVE', 'ARCHIVED')),
    version          bigint      not null default 1 check (version >= 1),
    created_at       timestamptz not null,
    updated_at       timestamptz not null
);

create index session_series_offering_idx on session_series (tenant_id, offering_id, status);

call enable_tenant_rls('session_series');

create table sessions (
    tenant_id   uuid        not null,
    id          uuid        primary key,
    -- A one-off session carries no series; the unique pair is what makes materialization idempotent.
    series_id   uuid        references session_series (id) on delete cascade,
    offering_id uuid        not null references offerings (id),
    starts_at   timestamptz not null,
    ends_at     timestamptz not null,
    status      text        not null check (status in ('SCHEDULED', 'CANCELLED')),
    capacity    integer     not null check (capacity >= 0),
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    unique (series_id, starts_at)
);

create index sessions_offering_idx on sessions (tenant_id, offering_id, starts_at);

call enable_tenant_rls('sessions');

create table session_pools (
    tenant_id  uuid    not null,
    session_id uuid    not null references sessions (id) on delete cascade,
    code       text    not null check (length(code) between 1 and 60),
    capacity   integer not null check (capacity >= 0),
    primary key (tenant_id, session_id, code)
);

call enable_tenant_rls('session_pools');
