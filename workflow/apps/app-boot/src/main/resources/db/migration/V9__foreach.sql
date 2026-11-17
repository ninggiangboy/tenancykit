-- A for-each walks its body once per item. The item and its index travel with the token, so a body node reads them
-- without any shared mutable state, and the loop's progress is one row that concurrent arrivals serialize on.
alter table workflow_token add column payload jsonb;
alter table workflow_token add column loop_node_id text
    check (loop_node_id is null or length(loop_node_id) between 1 and 64);

create table workflow_loop_state (
    instance_id uuid        not null,
    node_id     text        not null,
    tenant_id   uuid        not null,
    total       integer     not null check (total >= 0),
    started     integer     not null default 0 check (started >= 0),
    arrived     integer     not null default 0 check (arrived >= 0),
    items       jsonb       not null,
    outputs     jsonb       not null default '{}'::jsonb,
    errors      jsonb       not null default '{}'::jsonb,
    updated_at  timestamptz not null,
    primary key (instance_id, node_id)
);

call enable_tenant_rls('workflow_loop_state');
