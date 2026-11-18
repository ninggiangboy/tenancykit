-- The record of every erasure this tenant asked for, so a retry of one call replays its answer instead of erasing
-- something else.
--
-- The Tenant's reference is what ties a member to a person in the Tenant's own system, and cutting that tie is the point
-- of an erasure. This table therefore keeps only `sha256(tenant_id ‖ external_ref)`, computed by the application: a
-- retried call hashes the reference it was sent, finds this row and replays it, while the stored value cannot be turned
-- back into a reference to a person.
--
-- One row per reference of one tenant is the primary key, so a retry that slipped past the lookup cannot append a second
-- record of the same request.
create table erasure_requests (
    tenant_id uuid        not null,
    ref_hash  text        not null check (ref_hash ~ '^[0-9a-f]{64}$'),
    member_id uuid        not null,
    erased_at timestamptz not null,
    primary key (tenant_id, ref_hash),
    foreign key (tenant_id, member_id) references members (tenant_id, id)
);

call enable_tenant_rls('erasure_requests');

-- An erasure record is the answer a retry receives, so it outlives the request that wrote it: there is no update and no
-- delete for it. The member's own row and its ledger stay as an anonymous record keyed by the opaque id.
revoke update on erasure_requests from loyalty_app;
revoke delete on erasure_requests from loyalty_app;
