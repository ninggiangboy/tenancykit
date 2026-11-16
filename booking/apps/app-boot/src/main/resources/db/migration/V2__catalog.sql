-- What a Tenant may book and when it is open: resources with their own IANA zone, the weekly wall-clock pattern and the
-- dated exceptions to it. No booking exists in this migration; a resource here is the unit a claim is later made on.
--
-- Local wall-clock times are stored on purpose. A zone's rules change and a migration must not be needed when they do:
-- the zone id stays the source of truth and phase 5 resolves a local time into an instant at the moment it needs one.
-- The weekly rows are minutes from midnight, because PostgreSQL's `time` cannot hold the `24:00` the API accepts for a
-- close at the end of a day.
create table resources (
    tenant_id  uuid        not null,
    id         uuid        primary key,
    -- The Tenant's code: unique per tenant, immutable once chosen.
    code       text        not null check (length(code) between 1 and 60),
    name       text        not null check (length(name) between 1 and 200),
    -- An IANA zone id, checked against the JDK's tz database by the application.
    zone       text        not null check (length(zone) between 1 and 64),
    -- Seats are for fitting a merged table later; a resource without seats is simply not seat-limited.
    seats      integer     check (seats is null or seats >= 1),
    status     text        not null check (status in ('ACTIVE', 'ARCHIVED')),
    version    bigint      not null default 1 check (version >= 1),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (tenant_id, code)
);

-- The resource list pages newest first and breaks ties on the id, so the cursor is a range scan on one index.
create index resources_keyset_idx on resources (tenant_id, created_at desc, id desc);
create index resources_code_idx on resources (tenant_id, code);

call enable_tenant_rls('resources');

create table working_hours (
    tenant_id     uuid     not null,
    id            uuid     primary key,
    resource_id   uuid     not null references resources (id) on delete cascade,
    -- ISO weekday, 1 = Monday.
    weekday       smallint not null check (weekday between 1 and 7),
    -- Minutes from local midnight; the API's 24:00 is 1440, which no time column could hold.
    opens_minute  smallint not null check (opens_minute between 0 and 1439),
    closes_minute smallint not null check (closes_minute between 1 and 1440),
    check (closes_minute > opens_minute)
);

create index working_hours_resource_idx on working_hours (tenant_id, resource_id, weekday, opens_minute);

call enable_tenant_rls('working_hours');

create table calendar_exceptions (
    tenant_id   uuid  not null,
    id          uuid  primary key,
    resource_id uuid  not null references resources (id) on delete cascade,
    from_date   date  not null,
    to_date     date  not null,
    kind        text  not null check (kind in ('CLOSED', 'OPEN')),
    -- An OPEN exception's replacement hours: an array of {weekday, opens, closes} in local wall-clock form.
    hours       jsonb not null default '[]'::jsonb,
    check (to_date >= from_date)
);

create index calendar_exceptions_resource_idx on calendar_exceptions (tenant_id, resource_id, from_date);

call enable_tenant_rls('calendar_exceptions');
