-- Goals, conversions, their credits and the recompute runs a model change starts.
--
-- A conversion's touch chain is snapshotted on the row the first time credit is computed, so a recompute after the raw
-- click partition is dropped still reproduces the result. Credits are one row per credited click, summing exactly to the
-- conversion's value: linear credit uses largest-remainder rounding and the residual goes to the last touch.
--
-- `owning_campaign_id` is the campaign of the last click in the chain. Its model and window govern the conversion, and
-- every click in the chain is credited under it whatever campaign it belongs to; one conversion is credited once.

create table goals (
    id            uuid        primary key,
    tenant_id     uuid        not null,
    key           text        not null check (key ~ '^[a-z0-9_-]{1,64}$'),
    name          text        not null check (length(name) between 1 and 200),
    default_value numeric(19, 4),
    currency      text        check (currency is null or currency ~ '^[A-Z]{3}$'),
    status        text        not null check (status in ('ACTIVE', 'ARCHIVED')),
    version       bigint      not null default 1,
    created_at    timestamptz not null,
    updated_at    timestamptz not null
);

create unique index goals_key_idx on goals (tenant_id, key);
call enable_tenant_rls('goals');

create table conversions (
    id                uuid           primary key,
    tenant_id         uuid           not null,
    goal_id           uuid           not null,
    click_id          uuid,
    visitor_id        text           check (visitor_id is null or length(visitor_id) <= 128),
    value             numeric(19, 4) not null check (value >= 0),
    currency          text           not null check (currency ~ '^[A-Z]{3}$'),
    occurred_at       timestamptz    not null,
    source            text           not null check (source in ('POSTBACK', 'SNIPPET')),
    idempotency_key   text           not null check (length(idempotency_key) between 1 and 255),
    credit_status     text           not null check (credit_status in ('PENDING', 'COMPUTED', 'UNATTRIBUTED')),
    owning_campaign_id uuid,
    -- The chain is stored oldest first; it is snapshotted the first time credit is computed so a recompute still works
    -- after the raw events age out.
    touch_chain       jsonb          not null default '[]',
    computed_at       timestamptz,
    created_at        timestamptz    not null,
    unique (tenant_id, idempotency_key)
);

create index conversions_pending_idx on conversions (tenant_id, credit_status, created_at);
create index conversions_owning_campaign_idx on conversions (tenant_id, owning_campaign_id);
-- The composite key a credit's foreign key references; it must exist before conversion_credits is created.
alter table conversions add constraint conversions_tenant_id_id_key unique (tenant_id, id);
call enable_tenant_rls('conversions');

create table conversion_credits (
    tenant_id     uuid           not null,
    conversion_id uuid           not null,
    click_id      uuid           not null,
    link_id       uuid           not null,
    campaign_id   uuid,
    model         text           not null check (model in ('FIRST', 'LAST', 'LINEAR')),
    weight        numeric(9, 8)  not null check (weight >= 0 and weight <= 1),
    credit        numeric(19, 4) not null check (credit >= 0),
    primary key (tenant_id, conversion_id, click_id),
    foreign key (tenant_id, conversion_id) references conversions (tenant_id, id) on delete cascade
);

create index conversion_credits_campaign_idx on conversion_credits (tenant_id, campaign_id);
call enable_tenant_rls('conversion_credits');


create table recompute_runs (
    id            uuid        primary key,
    tenant_id     uuid        not null,
    campaign_id   uuid        not null,
    reason        text        not null,
    status        text        not null check (status in ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    cursor        uuid,
    started_at    timestamptz not null,
    finished_at   timestamptz,
    last_error    text
);

-- One run per campaign at a time: a second request joins the pending one instead of starting a parallel pass.
create unique index recompute_runs_active_idx on recompute_runs (tenant_id, campaign_id)
    where status in ('PENDING', 'RUNNING');
call enable_tenant_rls('recompute_runs');
