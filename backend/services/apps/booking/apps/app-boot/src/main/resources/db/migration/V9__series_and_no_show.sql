-- A repeating booking: one row carries the recurrence the Tenant sent, and each occurrence is an ordinary booking that
-- names it. The recurrence is stored as the lines of RFC 5545 the caller sent, not as expanded rows, so a series is
-- read and cancelled without rewriting what it means.
create table booking_series (
    tenant_id        uuid        not null,
    id               uuid        primary key,
    offering_id      uuid        not null,
    customer_ref     text        not null check (length(customer_ref) between 1 and 200),
    zone             text        not null check (length(zone) between 1 and 64),
    -- The local start the rule expands from, in the stored zone.
    dtstart          text        not null,
    rule             text[]      not null check (cardinality(rule) >= 1),
    mode             text        not null check (mode in ('ALL_OR_NOTHING', 'SKIP_CONFLICTS')),
    status           text        not null check (status in ('ACTIVE', 'CANCELLED')),
    duration_minutes integer     check (duration_minutes is null or duration_minutes >= 1),
    pool_code        text        check (pool_code is null or length(pool_code) between 1 and 60),
    version          bigint      not null default 1 check (version >= 1),
    created_at       timestamptz not null,
    updated_at       timestamptz not null
);

create index booking_series_created_idx on booking_series (tenant_id, created_at desc, id desc);

call enable_tenant_rls('booking_series');

-- A booking that was confirmed and whose customer did not arrive; the claims stay as history.
alter table bookings add column no_show_at timestamptz;
