-- The one aggregate of the booking context: a held, confirmed, cancelled or lapsed booking, and the resources it
-- claimed.
--
-- A booking is one row whatever it sells, because the lifecycle after the claim is the same for a place in a session
-- and a range on a resource: it starts HELD, and exactly one of CONFIRMED, CANCELLED, EXPIRED follows. The claims
-- themselves stay in the engine that made them, keyed by the booking id, which is what keeps a confirmation and the
-- row it confirms in one transaction.
--
-- The unique key on the idempotency key is the database's decision rather than a read that could race: a repeated
-- POST inserts nothing and the caller is answered with the row the first call wrote. A booking with no key carries a
-- null, and a null never conflicts, so an unkeyed create always inserts.
create table bookings (
    tenant_id           uuid        not null,
    id                  uuid        primary key,
    offering_id         uuid        not null,
    offering_kind       text        not null check (offering_kind in ('INTERVAL', 'CAPACITY')),
    customer_ref        text        not null check (length(customer_ref) between 1 and 200),
    status              text        not null check (status in ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'NO_SHOW')),
    qty                 integer     not null default 1 check (qty >= 1),
    session_id          uuid,
    pool_code           text        check (pool_code is null or length(pool_code) between 1 and 60),
    starts_at           timestamptz,
    ends_at             timestamptz,
    source              text        not null check (source in ('TENANT', 'GATE', 'WAITLIST')),
    hold_expires_at     timestamptz,
    extended_seconds    integer     not null default 0 check (extended_seconds >= 0),
    confirmed_at        timestamptz,
    cancelled_at        timestamptz,
    policy_code         text,
    series_id           uuid,
    idempotency_key     text,
    request_fingerprint text,
    version             bigint      not null default 1 check (version >= 1),
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    unique (tenant_id, idempotency_key)
);

-- The listing reads newest first with the id breaking the tie, so the cursor is one range scan.
create index bookings_created_idx on bookings (tenant_id, created_at desc, id desc);

-- The expiry sweep looks for the oldest lapsed holds of one status.
create index bookings_expiry_idx on bookings (tenant_id, status, hold_expires_at);

-- A Tenant asking what it sold for one offering over a window.
create index bookings_offering_idx on bookings (tenant_id, offering_id, starts_at);

call enable_tenant_rls('bookings');

-- Every resource a booking claimed, with the offering requirement's slot code. A capacity booking carries none: its
-- claim is a number of places in a pool, not a resource.
create table booking_resources (
    tenant_id   uuid not null,
    booking_id  uuid not null references bookings (id) on delete cascade,
    slot_code   text not null,
    resource_id uuid not null,
    primary key (tenant_id, booking_id, slot_code)
);

-- Which bookings hold a resource, the question a reschedule or a resource erasure asks.
create index booking_resources_resource_idx on booking_resources (tenant_id, resource_id);

call enable_tenant_rls('booking_resources');
