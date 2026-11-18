-- The Tenant's one points program, the ordered tiers a member climbs and the promotions that multiply what a line
-- earns. The program context owns all three tables; no other context reads them, and the read port in its api package
-- is the only door.

create table loyalty_programs (
    tenant_id          uuid        primary key,
    currency           text        not null check (currency ~ '^[A-Z]{3}$'),
    -- Minor units of `currency` that earn one point, such as 1000 for one point per ten units of a decimal currency.
    unit_amount        bigint      not null check (unit_amount >= 1),
    expiry_months      integer     not null default 12 check (expiry_months between 1 and 120),
    -- Days before a lot expires that the notice run announces it; 0 disables the notice.
    expiry_notice_days integer     not null default 30 check (expiry_notice_days between 0 and 365),
    tier_window_months integer     not null default 12 check (tier_window_months between 1 and 60),
    -- An IANA zone id: promotion windows, weekdays and time-of-day rules are evaluated in it.
    time_zone          text        not null default 'UTC' check (length(time_zone) between 1 and 64),
    -- Set by every tier change and cleared once the re-evaluation has run, so a member's tier is never computed from a
    -- list that changed under it.
    tiers_dirty        boolean     not null default true,
    version            bigint      not null default 1 check (version >= 1),
    created_at         timestamptz not null,
    updated_at         timestamptz not null
);

call enable_tenant_rls('loyalty_programs');

-- One row per tier of a Tenant. The code is the identity a caller knows: replacing the list by code keeps an existing
-- code's id, so a member's stored tier keeps pointing at the same row.
create table tiers (
    tenant_id       uuid          not null,
    id              uuid          not null,
    code            text          not null check (length(code) between 1 and 100),
    name            text          not null check (length(name) between 1 and 255),
    min_spend       bigint        not null check (min_spend >= 0),
    earn_multiplier numeric(4, 2) not null check (earn_multiplier between 1.00 and 10.00),
    position        integer       not null check (position >= 0),
    primary key (tenant_id, id),
    unique (tenant_id, code)
);

create index tiers_position_idx on tiers (tenant_id, position);

call enable_tenant_rls('tiers');

-- A promotion multiplies the points a matching line earns. `conditions` is one jsonb document holding the closed set of
-- condition kinds (categories, skus, store codes, ISO weekdays, tier codes and a local time range), so a new kind is a
-- new field with a vector, never a new syntax. An archived promotion is kept as history and no longer counts against
-- the cap.
create table promotions (
    tenant_id   uuid          not null,
    id          uuid          not null,
    name        text          not null check (length(name) between 1 and 255),
    status      text          not null check (status in ('ACTIVE', 'PAUSED', 'ARCHIVED')),
    -- Catalog order and the tie-break between two equal multipliers; the higher value wins.
    priority    integer       not null default 0,
    multiplier  numeric(4, 2) not null check (multiplier between 1.01 and 10.00),
    starts_at   timestamptz,
    ends_at     timestamptz,
    conditions  jsonb         not null default '{}'::jsonb,
    version     bigint        not null default 1 check (version >= 1),
    created_at  timestamptz   not null,
    updated_at  timestamptz   not null,
    primary key (tenant_id, id),
    check (starts_at is null or ends_at is null or ends_at >= starts_at)
);

-- The earn engine asks for the active promotions whose window contains one instant, so the index leads with the status
-- the query always filters on.
create index promotions_window_idx on promotions (tenant_id, status, starts_at);

call enable_tenant_rls('promotions');
