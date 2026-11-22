-- Definitions: a Tenant's workflow definitions and their immutable published versions.
--
-- A definition owns one draft graph that is edited in place under an optimistic version, and an append-only list of
-- published versions. Publishing copies the draft into a version row; an instance pins a version id, so a later draft
-- edit can never change what a running instance does.
--
-- The draft graph is stored as the JSON the Tenant sent, not as a shape the database understands: a draft is allowed to
-- be structurally wrong (that is what the checker reports), and only a version is guaranteed to be a valid graph.

create table workflow_definition (
    id             uuid        primary key,
    tenant_id      uuid        not null,
    code           text        not null check (length(code) between 1 and 64),
    name           text        not null check (length(name) between 1 and 200),
    status         text        not null check (status in ('DRAFT', 'PUBLISHED', 'RETIRED')),
    draft          jsonb,
    latest_version integer     not null default 0 check (latest_version >= 0),
    created_at     timestamptz not null,
    updated_at     timestamptz not null,
    version        bigint      not null default 1 check (version >= 1)
);

create unique index workflow_definition_code_idx on workflow_definition (tenant_id, lower(code));
create index workflow_definition_created_idx on workflow_definition (tenant_id, created_at desc);

call enable_tenant_rls('workflow_definition');

create table workflow_version (
    id            uuid        primary key,
    tenant_id     uuid        not null,
    definition_id uuid        not null references workflow_definition (id),
    version       integer     not null check (version >= 1),
    graph         jsonb       not null,
    content_hash  text        not null,
    published_at  timestamptz not null,
    published_by  text
);

create unique index workflow_version_number_idx on workflow_version (definition_id, version);

call enable_tenant_rls('workflow_version');

-- A published version is immutable: a trigger that tried to rewrite one is a bug, not a legitimate correction.
revoke update on workflow_version from workflow_app;
