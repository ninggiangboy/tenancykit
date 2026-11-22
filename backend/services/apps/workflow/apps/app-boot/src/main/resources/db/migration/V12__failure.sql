alter table workflow_step_run
    add column error_class text check (error_class in ('RETRYABLE', 'FATAL')),
    add column failure     text;

alter table workflow_instance
    add column failure_reason text,
    add column replayed_from  uuid;

alter table workflow_instance drop constraint if exists workflow_instance_status_check;
alter table workflow_instance
    add constraint workflow_instance_status_check
        check (status in ('RUNNING', 'WAITING', 'COMPLETED', 'FAILED', 'CANCELLED', 'COMPENSATING',
                          'FAILED_STUCK', 'COMPENSATION_FAILED'));

create index workflow_instance_replayed_idx on workflow_instance (replayed_from) where replayed_from is not null;

create table workflow_compensation_run (
    id          uuid        primary key,
    tenant_id   uuid        not null,
    instance_id uuid        not null references workflow_instance (id),
    step_run_id uuid        not null,
    node_id     text        not null,
    status      text        not null check (status in ('RUNNING', 'SUCCEEDED', 'FAILED')),
    attempts    integer     not null default 0 check (attempts >= 0),
    failure     text,
    started_at  timestamptz not null,
    finished_at timestamptz
);

create index workflow_compensation_run_instance_idx on workflow_compensation_run (instance_id, started_at);

call enable_tenant_rls('workflow_compensation_run');
