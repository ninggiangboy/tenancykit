-- The sending identity a tenant registered, per from-key.
--
-- Two modes, and the check constraints make a half-configured row impossible: a TENANT row without a role ARN, an
-- external id and an account id cannot be written, and a SHARED row cannot smuggle a role ARN in. That matters because
-- this table is written by ops until an admin API exists, so the database is the last line that catches a mistake.
--
-- No long-lived AWS secret is stored. That is the entire reason TENANT mode was chosen: the external id is encrypted
-- only because it is per-tenant configuration, not because it is a secret in AWS's threat model.
--
-- The unique key is (tenant_id, from_key, version) rather than (tenant_id, from_key), so a rotation inserts the new row
-- at version + 1 before disabling the old one, and both can exist during the swap.

create table mailer_credentials (
    id                     uuid        primary key,
    tenant_id              uuid        not null,
    from_key               text        not null check (length(from_key) between 1 and 64),
    mode                   text        not null check (mode in ('SHARED', 'TENANT')),
    region                 text        not null check (length(region) between 1 and 32),
    role_arn               text,
    external_id_ciphertext text,
    aws_account_id         text,
    from_address           text        not null check (length(from_address) between 3 and 320),
    from_name              text        check (length(from_name) between 1 and 128),
    reply_to_allowlist     text[]      not null default '{}',
    status                 text        not null check (status in ('ACTIVE', 'DISABLED')),
    version                bigint      not null check (version >= 1),
    last_verified_at       timestamptz,
    created_at             timestamptz not null,
    updated_at             timestamptz not null,
    unique (tenant_id, id),
    unique (tenant_id, from_key, version),
    check (mode = 'SHARED' or (role_arn is not null and external_id_ciphertext is not null and aws_account_id is not null)),
    check (mode = 'TENANT' or (role_arn is null and external_id_ciphertext is null))
);

-- The resolver's predicate: one active row per tenant and key, found without reading a disabled one.
create index mailer_credentials_active_idx on mailer_credentials (tenant_id, from_key) where status = 'ACTIVE';
-- The health probe's predicate: active rows nobody has verified recently.
create index mailer_credentials_stale_idx on mailer_credentials (last_verified_at) where status = 'ACTIVE';

call enable_tenant_rls('mailer_credentials');
