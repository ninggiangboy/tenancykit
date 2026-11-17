-- A join counts its arrivals in one conditional update, so concurrent branches neither lose a count nor fire twice.
create table workflow_join_state (
    instance_id uuid        not null,
    node_id     text        not null,
    tenant_id   uuid        not null,
    expected    int         not null check (expected > 0),
    arrived     int         not null default 0 check (arrived >= 0),
    outputs     jsonb       not null default '{}'::jsonb,
    updated_at  timestamptz not null,
    primary key (instance_id, node_id)
);

call enable_tenant_rls('workflow_join_state');
