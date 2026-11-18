-- Triggers: the ways work starts, the events that arrive, and the schedules that fire.
--
-- A trigger points at a definition and names what starts it: a suite app's signed webhook, an external system's signed
-- webhook, a manual call (which needs no row) or a cron schedule. An inbound event is kept for the early-event window,
-- deduplicated by the source's own event id, so a redelivery cannot start a second instance and an event that arrives
-- before its wait is not lost.

alter table workflow_instance add column idempotency_key text;
alter table workflow_instance add column request_hash text;

-- The promise a repeated manual call relies on: one instance per key, and the request hash says whether the body agrees.
create unique index workflow_instance_idempotency_idx on workflow_instance (tenant_id, idempotency_key)
    where idempotency_key is not null;

create table workflow_trigger (
    id                uuid        primary key,
    tenant_id         uuid        not null,
    name              text        not null check (length(name) between 1 and 200),
    kind              text        not null check (kind in ('APP', 'EXTERNAL', 'MANUAL', 'SCHEDULE')),
    source            text        not null check (length(source) between 1 and 32),
    event_type        text        check (length(event_type) between 1 and 200),
    definition_id     uuid        not null references workflow_definition (id),
    filter            jsonb,
    correlation       jsonb,
    input             jsonb,
    secret_ciphertext text,
    key_id            text,
    enabled           boolean     not null default true,
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    version           bigint      not null default 1 check (version >= 1),
    check ((kind in ('APP', 'EXTERNAL')) = (secret_ciphertext is not null))
);

create index workflow_trigger_source_idx on workflow_trigger (tenant_id, source, event_type) where enabled;

call enable_tenant_rls('workflow_trigger');

create table workflow_inbound_event (
    id          uuid        primary key,
    tenant_id   uuid        not null,
    source      text        not null,
    event_id    uuid        not null,
    event_type  text        not null,
    payload     jsonb       not null,
    received_at timestamptz not null,
    expires_at  timestamptz not null
);

-- The deduplication key: a redelivery inserts nothing, so the trigger fan-out runs once.
create unique index workflow_inbound_event_id_idx on workflow_inbound_event (tenant_id, source, event_id);
create index workflow_inbound_event_expiry_idx on workflow_inbound_event (expires_at);

call enable_tenant_rls('workflow_inbound_event');

create table workflow_schedule (
    id         uuid        primary key,
    tenant_id  uuid        not null,
    trigger_id uuid        not null unique references workflow_trigger (id),
    cron       text        not null check (length(cron) between 1 and 200),
    timezone   text        not null check (length(timezone) between 1 and 64),
    next_due   timestamptz not null,
    last_fired timestamptz,
    version    bigint      not null default 1 check (version >= 1)
);

create index workflow_schedule_due_idx on workflow_schedule (next_due);

call enable_tenant_rls('workflow_schedule');
