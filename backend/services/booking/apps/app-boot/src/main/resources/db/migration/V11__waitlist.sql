-- A waiting customer and the place they are waiting for.
--
-- A target is either a capacity session's pool or an interval offering's range; the columns carry whichever applies, so
-- one table serves both without inventing a second aggregate. A release writes a wake-up row in its own transaction and
-- leaves the matching work to the worker, so cancelling never waits on a scan.
create table waitlist_entries (
    tenant_id           uuid        not null,
    id                  uuid        primary key,
    offering_id         uuid        not null,
    customer_ref        text        not null check (length(customer_ref) between 1 and 200),
    session_id          uuid,
    pool_code           text        check (pool_code is null or length(pool_code) between 1 and 60),
    desired_starts_at   timestamptz,
    desired_ends_at     timestamptz,
    qty                 integer     not null check (qty >= 1),
    resource_id         uuid,
    status              text        not null check (status in ('WAITING', 'OFFERED', 'ACCEPTED', 'LAPSED', 'CANCELLED')),
    offered_booking_id  uuid,
    offered_until       timestamptz,
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    check (status <> 'OFFERED' or offered_booking_id is not null),
    check ((session_id is null) = (pool_code is null))
);

-- One waiting entry per customer and target: the partial index is both the join rule and what a retry hits.
create unique index waitlist_entries_waiting_idx on waitlist_entries
    (tenant_id, customer_ref, offering_id, coalesce(session_id, '00000000-0000-0000-0000-000000000000'::uuid))
    where status = 'WAITING';
create index waitlist_entries_scope_idx on waitlist_entries
    (tenant_id, offering_id, session_id, created_at)
    where status = 'WAITING';
create index waitlist_entries_booking_idx on waitlist_entries (tenant_id, offered_booking_id)
    where offered_booking_id is not null;

call enable_tenant_rls('waitlist_entries');

-- A released place, recorded cheaply: the worker reads the rows and runs the matcher outside the releasing transaction.
create table waitlist_wakeups (
    tenant_id   uuid        not null,
    id          uuid        primary key,
    scope       text        not null check (length(scope) between 1 and 100),
    created_at  timestamptz not null
);

create index waitlist_wakeups_due_idx on waitlist_wakeups (tenant_id, created_at);

call enable_tenant_rls('waitlist_wakeups');
