-- The runtime: one row per instance, one row per token that walks a graph, and the append-only step history.
--
-- Postgres is the only source of truth. A token is claimed with a lease, the node runs outside the transaction, and one
-- transaction then records the step and creates the tokens that follow. Every state change is a conditional update on
-- the status and the lease owner, so a worker that lost its lease can never write a second completion.

create table workflow_instance (
    id              uuid        primary key,
    tenant_id       uuid        not null,
    definition_id   uuid        not null,
    version_id      uuid        not null references workflow_version (id),
    version_number  integer     not null,
    status          text        not null
        check (status in ('RUNNING', 'WAITING', 'COMPLETED', 'FAILED', 'CANCELLED', 'COMPENSATING')),
    input           jsonb,
    variables       jsonb       not null default '{}'::jsonb,
    correlation_key text        check (length(correlation_key) <= 200),
    started_by      text,
    started_at      timestamptz not null,
    finished_at     timestamptz,
    version         bigint      not null default 1 check (version >= 1),
    check ((status in ('COMPLETED', 'FAILED', 'CANCELLED')) = (finished_at is not null))
);

create index workflow_instance_status_idx on workflow_instance (tenant_id, status);
create index workflow_instance_definition_idx on workflow_instance (tenant_id, definition_id, started_at desc);
create index workflow_instance_correlation_idx on workflow_instance (tenant_id, correlation_key)
    where correlation_key is not null;

call enable_tenant_rls('workflow_instance');

create table workflow_token (
    id             uuid        primary key,
    tenant_id      uuid        not null,
    instance_id    uuid        not null references workflow_instance (id),
    node_id        text        not null check (length(node_id) between 1 and 64),
    status         text        not null check (status in ('READY', 'RUNNING', 'WAITING', 'DONE', 'FAILED')),
    parent_token_id uuid,
    branch_key     text,
    iteration      integer     not null default 0 check (iteration >= 0),
    attempt        integer     not null default 0 check (attempt >= 0),
    available_at   timestamptz not null,
    lease_owner    text,
    lease_until    timestamptz,
    created_at     timestamptz not null,
    updated_at     timestamptz not null,
    check ((status = 'RUNNING') = (lease_owner is not null)),
    check ((status = 'RUNNING') = (lease_until is not null))
);

-- The claim reads due READY tokens only; the partial index keeps that predicate cheap as history grows.
create index workflow_token_claim_idx on workflow_token (available_at) where status = 'READY';
create index workflow_token_instance_idx on workflow_token (instance_id);
-- The sweeper finds expired leases and the completion check counts live tokens; both use this pair.
create index workflow_token_lease_idx on workflow_token (lease_until) where status = 'RUNNING';
create index workflow_token_live_idx on workflow_token (instance_id, status)
    where status in ('READY', 'RUNNING', 'WAITING');

call enable_tenant_rls('workflow_token');

create table workflow_step_run (
    id              uuid        primary key,
    tenant_id       uuid        not null,
    instance_id     uuid        not null references workflow_instance (id),
    token_id        uuid        not null,
    node_id         text        not null,
    attempt         integer     not null check (attempt >= 1),
    started_at      timestamptz not null,
    finished_at     timestamptz not null,
    outcome         text        not null check (outcome in ('SUCCEEDED', 'FAILED')),
    request         jsonb,
    response        jsonb,
    idempotency_key text        not null check (length(idempotency_key) = 64),
    unique (token_id, attempt)
);

create index workflow_step_run_instance_idx on workflow_step_run (instance_id, finished_at);
create index workflow_step_run_token_idx on workflow_step_run (token_id);

call enable_tenant_rls('workflow_step_run');

-- A step is evidence: what was tried and what came back. Retention may delete an old row with its instance, but nothing
-- may rewrite one, so an at-least-once retry cannot leave a second story behind.
revoke update on workflow_step_run from workflow_app;
