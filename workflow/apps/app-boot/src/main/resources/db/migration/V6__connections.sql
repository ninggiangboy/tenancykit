-- Outbound calls: a Tenant-owned connection whose secret is write-only, and the Platform credential a Tenant
-- registers so the engine can mint a token per audience.
--
-- A secret is stored as an envelope carrying the key id that wrote it, so a deployment can rotate its key and still
-- read what older envelopes hold. No plaintext secret is ever stored, and no read returns one.

create table workflow_connection (
    id                uuid        primary key,
    tenant_id         uuid        not null,
    name              text        not null check (length(name) between 1 and 64),
    kind              text        not null
        check (kind in ('BEARER', 'BASIC', 'API_KEY', 'HMAC', 'NONE')),
    allowed_host      text        not null check (length(allowed_host) between 1 and 255),
    secret_ciphertext text,
    secret_key_id     text,
    headers           jsonb       not null default '{}'::jsonb,
    verified_at       timestamptz,
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    version           bigint      not null default 1 check (version >= 1),
    check ((secret_ciphertext is null) = (secret_key_id is null))
);

create unique index workflow_connection_name_idx on workflow_connection (tenant_id, lower(name));

call enable_tenant_rls('workflow_connection');

create table workflow_platform_credential (
    tenant_id         uuid        primary key,
    client_id         text        not null,
    secret_ciphertext text        not null,
    secret_key_id     text        not null,
    status            text        not null check (status in ('ACTIVE', 'INVALID')),
    updated_at        timestamptz not null
);

call enable_tenant_rls('workflow_platform_credential');
