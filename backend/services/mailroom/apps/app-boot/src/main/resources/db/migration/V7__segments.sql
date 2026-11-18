-- Dynamic segments (context `audience`).
--
-- `filter` is a typed JSON AST (see SegmentFilter). It is validated at write time and again before evaluation, and
-- compiled to parameterised SQL over `receivers.attributes`; no expression text is ever concatenated.
create table segments (
    id         uuid        primary key,
    tenant_id  uuid        not null,
    name       text        not null check (length(name) between 1 and 200),
    filter     jsonb       not null,
    version    bigint      not null default 1 check (version >= 1),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (tenant_id, name)
);

call enable_tenant_rls('segments');
