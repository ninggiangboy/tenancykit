-- The places of a capacity session: one row per seat.
--
-- A claim takes rows with FOR UPDATE SKIP LOCKED, so concurrent callers take different places instead of queueing on
-- one counter and there is no count column to drift. A place is free exactly when its row is AVAILABLE, which is what
-- the remaining count a Tenant sees is read from.
create table capacity_units (
    tenant_id   uuid        not null,
    id          uuid        primary key,
    session_id  uuid        not null references sessions (id) on delete cascade,
    pool_code   text        not null check (length(pool_code) between 1 and 60),
    status      text        not null check (status in ('AVAILABLE', 'HELD', 'BOOKED')),
    booking_id  uuid,
    held_until  timestamptz,
    check ((status = 'AVAILABLE') = (booking_id is null and held_until is null)),
    check (status <> 'HELD' or held_until is not null)
);

create index capacity_units_pool_idx on capacity_units (tenant_id, session_id, pool_code, status);

create index capacity_units_booking_idx on capacity_units (tenant_id, booking_id);

call enable_tenant_rls('capacity_units');
