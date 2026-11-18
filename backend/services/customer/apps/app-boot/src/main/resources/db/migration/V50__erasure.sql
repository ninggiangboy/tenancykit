-- The record of every erasure this tenant asked for, so a retry of one call replays the answer instead of erasing
-- something else.
--
-- The Tenant's reference is what ties a customer to a person in the Tenant's own system, and cutting that tie is the
-- point of an erasure. This table therefore keeps only `sha256(tenant_id ‖ external_ref)`, computed by the
-- application: a retried call hashes the reference it was sent, finds this row and replays it, while the stored value
-- cannot be turned back into a reference to a person.
--
-- One row per reference of one tenant is the primary key, so a retry that slipped past the lookup cannot append a
-- second record of the same request.
create table erasure_requests (
    tenant_id   uuid        not null,
    ref_hash    text        not null check (ref_hash ~ '^[0-9a-f]{64}$'),
    customer_id uuid        not null references customers (id),
    erased_at   timestamptz not null,
    primary key (tenant_id, ref_hash)
);

call enable_tenant_rls('erasure_requests');

-- A consent record stays as the proof that an agreement existed and when it changed, so the history stays immutable
-- to the application role. The two free-text columns the Tenant fills are the exception: either can hold the person's
-- data, so an erasure clears them. The grant is written per column, which is what keeps status, topic and the rest of
-- a record unwritable even for the erasure path.
revoke update on consent_events from customer_app;
grant update (source, evidence_ref) on consent_events to customer_app;
