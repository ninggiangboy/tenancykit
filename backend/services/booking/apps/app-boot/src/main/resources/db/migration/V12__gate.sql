-- The Tenant's public-gate settings: the origins its customer's browser may call from, and the longest token it may mint.
--
-- Kept apart from the booking timing settings because it is an HTTP-edge policy, read on every preflight and token
-- issue rather than by the engines.
create table gate_settings (
    tenant_id             uuid        primary key,
    allowed_origins       text[]      not null default '{}' check (cardinality(allowed_origins) <= 20),
    max_token_ttl_seconds integer     not null default 900 check (max_token_ttl_seconds between 1 and 3600),
    updated_at            timestamptz not null
);

call enable_tenant_rls('gate_settings');
