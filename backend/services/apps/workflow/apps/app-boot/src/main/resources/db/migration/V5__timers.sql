-- Durable time: a timer row per parked deadline and a subscription per parked event wait.
--
-- A token is parked by moving it to WAITING inside the same transaction that records its step. The row that resolves
-- the token — the timer sweep or an arriving event — wins with one conditional update (WAITING -> READY); the loser
-- updates nothing. Whichever side wins removes the other's row, so a wait cannot resume twice.

alter table workflow_token
    add column resume_branch  text,
    add column resume_payload jsonb;

create table workflow_timer (
    id          uuid        primary key,
    tenant_id   uuid        not null,
    instance_id uuid        not null references workflow_instance (id),
    token_id    uuid        not null references workflow_token (id),
    kind        text        not null check (kind in ('DELAY', 'WAIT_TIMEOUT', 'TASK_DUE', 'RETRY')),
    due_at      timestamptz not null,
    fired_at    timestamptz,
    created_at  timestamptz not null
);

-- The sweep reads due, unfired timers only; the partial index keeps that predicate cheap.
create index workflow_timer_due_idx on workflow_timer (due_at) where fired_at is null;
create index workflow_timer_token_idx on workflow_timer (token_id) where fired_at is null;
create index workflow_timer_instance_idx on workflow_timer (instance_id);

call enable_tenant_rls('workflow_timer');

create table workflow_wait_subscription (
    id              uuid        primary key,
    tenant_id       uuid        not null,
    instance_id     uuid        not null references workflow_instance (id),
    token_id        uuid        not null references workflow_token (id),
    source          text        not null,
    event_type      text        not null,
    registered_at   timestamptz not null,
    expires_at      timestamptz not null,
    resolved_at     timestamptz
);

-- An arriving event looks for open subscriptions by source and type; an expiry cancels by token.
create index workflow_wait_match_idx on workflow_wait_subscription (tenant_id, source, event_type)
    where resolved_at is null;
create index workflow_wait_token_idx on workflow_wait_subscription (token_id) where resolved_at is null;

call enable_tenant_rls('workflow_wait_subscription');
