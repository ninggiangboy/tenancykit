-- A range claimed on one resource, so no two bookings overlap on it.
--
-- The promise is the database's: an exclusion constraint on the half-open range, so back-to-back claims are legal and
-- an overlapping one is refused. The constraint cannot see `expires_at`, so a claim removes the same resource's lapsed
-- holds before it inserts, and the `btree_gist` extension supplies the equality operators the gist index needs on a
-- uuid. A confirmed claim carries no expiry.
create extension if not exists btree_gist;

create table interval_claims (
    tenant_id   uuid        not null,
    id          uuid        primary key,
    resource_id uuid        not null references resources (id),
    booking_id  uuid        not null,
    -- The claimed range, buffers included, as a half-open `[start, end)` range of instants.
    during      tstzrange   not null,
    -- When the hold lapses, or null for a committed claim.
    expires_at  timestamptz,
    created_at  timestamptz not null,
    unique (tenant_id, booking_id, resource_id)
);

create index interval_claims_booking_idx on interval_claims (tenant_id, booking_id);

-- No two claims of one resource in one tenant may overlap; a non-overlapping pair inserts freely.
alter table interval_claims add constraint interval_claims_no_overlap
    exclude using gist (tenant_id with =, resource_id with =, during with &&);

call enable_tenant_rls('interval_claims');
