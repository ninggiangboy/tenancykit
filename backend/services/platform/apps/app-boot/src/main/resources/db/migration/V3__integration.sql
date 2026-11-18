-- Tenant Systems and the API credentials they authenticate with, plus the token endpoint's rate limiter.
--
-- A Tenant System belongs to one Tenant and a credential belongs to one Tenant System, so both carry the tenant id
-- and use the suite's row-level-security template. The platform scope may see every row: Platform Administration
-- manages tenant systems for all tenants, and the token endpoint must find a credential before any tenant is bound.

create table tenant_systems (
    id         uuid primary key,
    tenant_id  uuid        not null references tenants (id),
    name       text        not null check (length(name) between 1 and 100),
    status     text        not null check (status in ('ACTIVE', 'DISABLED')),
    created_at timestamptz not null,
    version    bigint      not null default 1,
    unique (tenant_id, id)
);

create unique index tenant_systems_name_idx on tenant_systems (tenant_id, lower(name));
call enable_tenant_rls('tenant_systems');

create policy platform_writer on tenant_systems
    using (app_tenant_id() = '00000000-0000-7000-8000-000000000001')
    with check (app_tenant_id() = '00000000-0000-7000-8000-000000000001');

-- Only the SHA-256 digest of the secret is stored. key_id is unique across the platform so a presented
-- credential can never be ambiguous; row-level security still hides other tenants' credentials from a tenant.
create table api_credentials (
    id               uuid primary key,
    tenant_id        uuid        not null references tenants (id),
    tenant_system_id uuid        not null,
    key_id           text        not null unique,
    secret_hash      bytea       not null check (octet_length(secret_hash) = 32),
    scopes           text[]      not null,
    label            text        not null,
    status           text        not null check (status in ('ACTIVE', 'REVOKED')),
    created_at       timestamptz not null,
    expires_at       timestamptz,
    revoked_at       timestamptz,
    unique (tenant_id, id),
    foreign key (tenant_id, tenant_system_id) references tenant_systems (tenant_id, id)
);

create index api_credentials_system_idx on api_credentials (tenant_system_id);
call enable_tenant_rls('api_credentials');

create policy platform_writer on api_credentials
    using (app_tenant_id() = '00000000-0000-7000-8000-000000000001')
    with check (app_tenant_id() = '00000000-0000-7000-8000-000000000001');

-- One token bucket per client id, shared by every instance. The table holds no business data: the decision is one
-- function call that locks one row, and a worker removes buckets idle past their TTL.
create table rate_limit_buckets (
    bucket_key text             primary key,
    tokens     double precision not null check (tokens >= 0),
    updated_at timestamptz      not null,
    expires_at timestamptz      not null
);

create index rate_limit_buckets_expiry_idx on rate_limit_buckets (expires_at);

create function try_acquire_rate_limit(
    p_key text,
    p_burst double precision,
    p_refill_per_second double precision,
    p_ttl_seconds integer
) returns boolean
    language plpgsql
as $$
declare
    v_now       timestamptz := clock_timestamp();
    v_tokens    double precision;
    v_updated   timestamptz;
    v_available double precision;
begin
    insert into rate_limit_buckets (bucket_key, tokens, updated_at, expires_at)
    values (p_key, p_burst, v_now, v_now + make_interval(secs => p_ttl_seconds))
    on conflict (bucket_key) do nothing;

    select tokens, updated_at into v_tokens, v_updated
    from rate_limit_buckets
    where bucket_key = p_key
    for update;

    v_available := least(
        p_burst,
        v_tokens + extract(epoch from (v_now - v_updated))::double precision * p_refill_per_second);

    if v_available >= 1 then
        update rate_limit_buckets
        set tokens = v_available - 1,
            updated_at = v_now,
            expires_at = v_now + make_interval(secs => p_ttl_seconds)
        where bucket_key = p_key;
        return true;
    end if;

    update rate_limit_buckets
    set tokens = v_available,
        updated_at = v_now,
        expires_at = v_now + make_interval(secs => p_ttl_seconds)
    where bucket_key = p_key;
    return false;
end;
$$;

grant execute on function try_acquire_rate_limit(text, double precision, double precision, integer) to platform_app;
