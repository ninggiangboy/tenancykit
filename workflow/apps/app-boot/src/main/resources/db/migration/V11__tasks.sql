-- Human tasks: a workflow stops at a task, a person or a system decides, and the token carries the decision on.
--
-- A task is assigned to one user or to a group. The group's members are copied onto the task when it is created, so an
-- open task answers for the people who were there when it started rather than for whoever joins later.

create table workflow_task_group (
    id         uuid        primary key,
    tenant_id  uuid        not null,
    code       text        not null check (code ~ '^[a-z][a-z0-9_-]{1,63}$'),
    name       text        not null check (length(name) between 1 and 200),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null default 1 check (version >= 1)
);

create unique index workflow_task_group_code_idx on workflow_task_group (tenant_id, code);
call enable_tenant_rls('workflow_task_group');

create table workflow_task_group_member (
    group_id  uuid        not null references workflow_task_group (id) on delete cascade,
    tenant_id uuid        not null,
    user_id   uuid        not null,
    added_at  timestamptz not null,
    primary key (group_id, user_id)
);

create index workflow_task_group_member_user_idx on workflow_task_group_member (tenant_id, user_id);
call enable_tenant_rls('workflow_task_group_member');

create table workflow_task (
    id                     uuid        primary key,
    tenant_id              uuid        not null,
    instance_id            uuid        not null references workflow_instance (id),
    token_id               uuid        not null,
    node_id                text        not null check (length(node_id) between 1 and 64),
    title                  text        not null check (length(title) between 1 and 500),
    form                   jsonb,
    assignee_user_id       uuid,
    assignee_group_id      uuid        references workflow_task_group (id),
    status                 text        not null
        check (status in ('OPEN', 'CLAIMED', 'COMPLETED', 'CANCELLED', 'ESCALATED', 'EXPIRED')),
    outcomes               jsonb       not null default '[]'::jsonb,
    due_at                 timestamptz,
    escalation_group_id   uuid        references workflow_task_group (id),
    escalate_at            timestamptz,
    expires_at             timestamptz,
    on_expire              text,
    decision               text,
    output                 jsonb,
    completed_by           text,
    completed_via          text        check (completed_via in ('USER', 'SYSTEM')),
    completed_at           timestamptz,
    created_at             timestamptz not null,
    updated_at             timestamptz not null,
    version                bigint      not null default 1 check (version >= 1),
    check ((assignee_user_id is null) <> (assignee_group_id is null)),
    check ((status = 'COMPLETED') = (completed_at is not null))
);

create index workflow_task_inbox_idx on workflow_task (tenant_id, status, due_at);
create index workflow_task_instance_idx on workflow_task (instance_id);
create index workflow_task_token_idx on workflow_task (token_id);
create index workflow_task_user_idx on workflow_task (tenant_id, assignee_user_id) where assignee_user_id is not null;
create index workflow_task_group_idx on workflow_task (tenant_id, assignee_group_id) where assignee_group_id is not null;
create index workflow_task_sweep_idx on workflow_task (tenant_id, escalate_at, expires_at)
    where status in ('OPEN', 'CLAIMED', 'ESCALATED');

call enable_tenant_rls('workflow_task');

-- The members a group had when the task was created. Visibility reads this, never the live group.
create table workflow_task_assignee (
    task_id   uuid        not null references workflow_task (id) on delete cascade,
    tenant_id uuid        not null,
    user_id   uuid        not null,
    primary key (task_id, user_id)
);

create index workflow_task_assignee_user_idx on workflow_task_assignee (tenant_id, user_id);
call enable_tenant_rls('workflow_task_assignee');
